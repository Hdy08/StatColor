#!/bin/bash
# 从 dex 里抽出指定类的 smali（比 baksmali 整体反汇编快得多）
#   用法: ./dumpsmali.sh <dex 或 apk> <类名子串> [更多子串...]
# 依赖: apt install libsmali-java
set -e
JARS=$(ls /usr/share/java/baksmali.jar /usr/share/java/dexlib2.jar \
            /usr/share/java/smali-util.jar /usr/share/java/guava.jar \
            /usr/share/java/jcommander.jar 2>/dev/null | tr '\n' ':')
HERE=$(cd "$(dirname "$0")" && pwd)
[ -d "$HERE/tools" ] || { mkdir -p "$HERE/tools"; javac -encoding UTF-8 -nowarn \
    -cp "$JARS" -d "$HERE/tools" "$HERE/DumpSmali.java"; }
java -cp "$JARS:$HERE/tools" DumpSmali "$@"
