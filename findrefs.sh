#!/bin/bash
# 扫描 dex，找出「谁引用了某个方法/字段」
#   用法: ./findrefs.sh <dex> "drawable/Drawable;->setTint(" [更多子串...]
# 依赖: apt install libsmali-java
set -e
JARS=$(ls /usr/share/java/baksmali.jar /usr/share/java/dexlib2.jar \
            /usr/share/java/smali-util.jar /usr/share/java/guava.jar \
            /usr/share/java/jcommander.jar 2>/dev/null | tr '\n' ':')
HERE=$(cd "$(dirname "$0")" && pwd)
[ -d "$HERE/tools" ] || { mkdir -p "$HERE/tools"; javac -encoding UTF-8 -nowarn \
    -cp "$JARS" -d "$HERE/tools" "$HERE/FindRefs.java"; }
java -cp "$JARS:$HERE/tools" FindRefs "$@"
