#!/system/bin/sh
# This helper writes only inside the module. Optional root prefix is for host fixture tests.
MODDIR=$1
ROOT=${2:-}
FONT="$MODDIR/system/fonts/AuraEmojiSystem.ttf"
[ -s "$FONT" ] || exit 1
LOG="$MODDIR/install.log"
: > "$LOG"
PATCHED=0
mkdir -p "$MODDIR/system/etc"
for name in fonts.xml font_fallback.xml font-fallback.xml; do
    src="$ROOT/system/etc/$name"
    dst="$MODDIR/system/etc/$name"
    [ -f "$src" ] || { rm -f "$dst"; continue; }
    if awk -f "$MODDIR/patch-config.awk" "$src" > "$dst.tmp" && [ -s "$dst.tmp" ]; then
        chmod 0644 "$dst.tmp"
        mv -f "$dst.tmp" "$dst"
        printf 'Fallback configured: /system/etc/%s\n' "$name" >> "$LOG"
        PATCHED=$((PATCHED + 1))
    else
        rm -f "$dst.tmp" "$dst"
        printf 'Unsupported configuration skipped: %s\n' "$name" >> "$LOG"
    fi
done
# OEM apps sometimes request a vendor emoji filename directly. Overlay only existing TTFs.
# Never replace the stock NotoColorEmoji.ttf: it remains the fallback for missing glyphs.
[ -f "$MODDIR/aliases.list" ] && while IFS= read -r old; do
    case "$old" in system/fonts/*|system/product/fonts/*|system/system_ext/fonts/*|system/vendor/fonts/*)
        [ "$old" = system/fonts/AuraEmojiSystem.ttf ] || rm -f "$MODDIR/$old" ;;
    esac
done < "$MODDIR/aliases.list"
: > "$MODDIR/aliases.list"
for dir in system/fonts product/fonts system/product/fonts system_ext/fonts vendor/fonts; do
    for file in "$ROOT/$dir/"*Emoji*.ttf "$ROOT/$dir/"*emoji*.ttf; do
        [ -f "$file" ] || continue
        base=${file##*/}
        case "$base" in NotoColorEmoji.ttf|AuraEmojiSystem.ttf) continue ;; esac
        case "$dir" in
            system/*) relative="$dir/$base" ;;
            *) relative="system/$dir/$base" ;;
        esac
        dst="$MODDIR/$relative"
        mkdir -p "${dst%/*}"
        [ -e "$dst" ] || ln "$FONT" "$dst" 2>/dev/null || cp "$FONT" "$dst" || exit 1
        printf '%s\n' "$relative" >> "$MODDIR/aliases.list"
        printf 'OEM font overlay: /%s/%s\n' "$dir" "$base" >> "$LOG"
    done
done
[ "$PATCHED" -gt 0 ] || exit 1
chmod 0644 "$FONT" "$LOG" "$MODDIR/aliases.list"
