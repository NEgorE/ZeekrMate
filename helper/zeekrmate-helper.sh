#!/system/bin/sh
# ZeekrMate helper: процесс с правами ADB-shell (uid 2000).
# Класс демона лежит в установленном APK. Этот скрипт его поднимает.

PKG="com.zeekrmate.app"
NAME="zeekrmate_helper"
CLASS="com.zeekrmate.app.helper.HelperDaemon"
LOG="/data/local/tmp/zeekrmate-helper.log"
WRAPPER="/data/local/tmp/zeekrmate-helper-run.sh"

APK=`pm path "$PKG" 2>/dev/null | sed -n '1s/^package://p'`
if [ -z "$APK" ]; then
  echo "ZeekrMate не установлен (pm path $PKG пустой)"
  exit 1
fi

PIDS=`ps -A -o PID,NAME 2>/dev/null | awk '$2=="zeekrmate_helper"{print $1}'`
if [ -n "$PIDS" ]; then
  kill -9 $PIDS 2>/dev/null
  sleep 1
fi

echo "start apk=$APK" > "$LOG"

# Отдельный wrapper: редирект лога принадлежит демону, а не pty adb.
# Иначе `&` + выход adb shell шлёт SIGHUP, JVM умирает до первой строки.
printf '%s\n' \
  '#!/system/bin/sh' \
  "export CLASSPATH='$APK'" \
  "exec app_process /system/bin --nice-name=$NAME $CLASS" \
  > "$WRAPPER"
chmod 755 "$WRAPPER"

if command -v setsid >/dev/null 2>&1; then
  setsid sh "$WRAPPER" </dev/null >>"$LOG" 2>&1 &
elif command -v nohup >/dev/null 2>&1; then
  nohup sh "$WRAPPER" </dev/null >>"$LOG" 2>&1 &
else
  sh "$WRAPPER" </dev/null >>"$LOG" 2>&1 &
fi
echo "spawn pid $!" >> "$LOG"

i=0
while [ $i -lt 10 ]; do
  if grep -q "LISTEN" "$LOG" 2>/dev/null; then
    echo "helper слушает 0.0.0.0:18790"
    echo "лог: $LOG"
    exit 0
  fi
  if grep -qiE "Error:|Exception|ClassNotFound|Could not find|Aborted" "$LOG" 2>/dev/null; then
    break
  fi
  sleep 1
  i=$((i+1))
done

# Если фон молчит — короткий запуск без отсоединения, чтобы ошибка точно попала в лог.
if ! grep -q "LISTEN" "$LOG" 2>/dev/null; then
  echo "--- probe ---" >> "$LOG"
  CLASSPATH="$APK" app_process /system/bin "$CLASS" >>"$LOG" 2>&1 &
  PROBE=$!
  sleep 3
  kill -9 $PROBE 2>/dev/null
  wait $PROBE 2>/dev/null
fi

echo "не стартовал, лог:"
cat "$LOG"
exit 1
