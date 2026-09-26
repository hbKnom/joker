MODDIR=${0%/*}
# Fix SELinux label on the module directory (chained to every subdirectory).
# FolkPatch/APatch may install modules with an isolated MLS label
# (e.g. adb_data_file:s0:c139,c257,c512,c768); zygote cannot read that, so
# Zygisk Next fails to load our native loader with `avc: denied { read }`.
# The plain adb_data_file:s0 label is readable, but newer FolkPatch/APatch
# sepolicy does NOT grant zygote { execute } on adb_data_file, so dlopen of
# zygisk/arm64-v8a.so fails (`avc: denied { execute }`, "not preloaded").
# Try the Magisk-ecosystem label magisk_file first (zygote has read+execute
# on it there); fall back to adb_data_file:s0 which pairs with sepolicy.rule.
for path in "$MODDIR" "$MODDIR/zygisk" "$MODDIR/zygisk/arm64-v8a.so" "$MODDIR/payload" /data/adb/wekit_zygisk; do
  chcon u:object_r:magisk_file:s0 "$path" 2>/dev/null || \
  chcon u:object_r:adb_data_file:s0 "$path" 2>/dev/null || true
done
