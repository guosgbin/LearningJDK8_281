#!/bin/bash

# 不知道为什么有的包下的java文件没有编译成 class 文件成功

# 设置开始时间
starttime=`date +%s`

echo "===========编译开始=============="
echo "--设置基本参数--"
echo ""

# 获取当前脚本的路径
shell_dir=$(cd "$(dirname "$0")";pwd)
echo "shell_dir is : $shell_dir"
echo ""

# 切换到脚本目录下
echo "change to $shell_dir"
cd $shell_dir
echo ""

# 获取当前脚本路径
cur_dir=`pwd`
echo "----cur_dir----"
echo "cur_dir is : $cur_dir"
echo ""

# 设置文件列表 及 文件的路径
filelist=$cur_dir"/filelist.txt"
echo "----filelist----"
echo "filelist is : $filelist"
echo ""

# 设置需要的jar包路径
# rt.jar 包路径 $JAVA_HOME/jre/lib/rt.jar
#rt_path=/Library/Java/JavaVirtualMachines/jdk1.8.0_281.jdk/Contents/Home/jre/lib/rt.jar
# tools.jar 包路径 $JAVA_HOME/lib/tools.jar
#tools_path=/Library/Java/JavaVirtualMachines/jdk1.8.0_281.jdk/Contents/Home/lib/tools.jar
#extlib=$rt_path:$tools_path

extlib=$cur_dir"/rt.jar:"$cur_dir"/tools.jar"
echo "----extlib----"
echo "extlib is : $extlib"
echo ""


# 切换到jdk src 源码目录
cd ..
src_project=`pwd`
src_dir=$src_project/src/main/java
echo "----src_dir----"
echo "src dir is : $src_dir"
echo ""

# 设置class文件输出路径
src_class=$src_project"/classes"
echo "----src_class----"
echo "src_class is : $src_class"
echo ""

# 将project的src目录下的所有java文件的全量名称存入到project/scripts/filelist.txt文件中
rm -rf $filelist
find $src_dir -name "*.java" > $filelist
echo ""

# $src_class是存放编译的class文件的目录
rm -rf $src_class
mkdir $src_class
echo "after recreate classes"
echo ""

echo "===========开始编译=============="
echo "编译中..."
# 批量编译java文件
# 编码：-encoding utf-8
# 依赖库以冒号:隔开
#javac -J-Xms1024m -J-Xmx1024m -sourcepath /Volumes/myplace/jdk8src/src -cp /Volumes/myplace/jdk8src/scripts/rt.jar:/Volumes/myplace/jdk8src/scripts/tools.jar -d /Volumes/myplace/jdk8src/classes -g @/Volumes/myplace/jdk8src/scripts/filelist.txt
echo "javac -J-Xms1024m -J-Xmx1024m -sourcepath source_code -cp /path/rt.jar:/path/tools.jar -d classes -g @filelist.txt"
#javac -J-Xms1024m -J-Xmx1024m -sourcepath $src_dir -cp $extlib -d $src_class -g @$filelist  >> log.txt 2>&1
javac -J-Xms1024m -J-Xmx1024m -sourcepath $src_dir -cp $extlib -d $src_class -g @$filelist > ./log/log.txt 2>&1
echo "编译中..."
echo "===========编译结束=============="
echo ""

# 进入classes目录
cd $src_class
cur=`pwd`
echo "change to classpath dir, current dir is : $cur"
echo ""

# 将class文件打包成jar包
echo "run command : jar cf0 rt_debug.jar *"
echo "package classes to rt_debug.jar"
jar cf0 rt_debug.jar *
echo ""

# 指定新jar包的存放路径
rt_debug_endorsed_dir=/Library/Java/JavaVirtualMachines/jdk1.8.0_281.jdk/Contents/Home/jre/lib/endorsed
echo "target dir is : $rt_debug_endorsed_dir"
echo ""

#如果文件夹不存在，创建文件夹
if [ ! -d $rt_debug_endorsed_dir ]; then
  # echo "passwd" | sudo -S shutdown -P now
  echo "654321" | sudo -S mkdir $rt_debug_endorsed_dir -P now
  echo "create target dir"
  echo ""
fi

# 复制jar包到指定路径
echo "copy rt_debug.jar to target dir: $rt_debug_endorsed_dir"
echo "654321" | sudo -S mv rt_debug.jar $rt_debug_endorsed_dir

echo ""
echo "=====操作结束===="

echo ""
endtime=`date +%s`
echo "本次运行时间： "$((endtime-starttime))"s"
