set -e
SRC=$(mktemp $TMPDIR/Grinder.Asm.Gdb.Source.XXXXXXXX.s)
MAIN=$(mktemp $TMPDIR/Grinder.Asm.Gdb.Dylib.XXXXXXXX.exe)
function cleanup {
  echo "Removing $SRC $MAIN"
  rm "$SRC" "$MAIN"
}
trap cleanup EXIT

pbpaste > "$SRC"
echo '
.globl _main
_main:
movq $10, %rdi
jmp _grinderEntry
' >> "$SRC"
gcc "$SRC" -o "$MAIN"
lldb "$MAIN"
# echo $?
