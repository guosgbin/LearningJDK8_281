#!/bin/bash

echo "====================操作开始==================="

# 设置开始时间
starttime=`date +%s`

# 进入classes目录
cd /Users/uxindylan/zDylanKwok/IDEAProject/Personal/LearningJDK8_281/target/classes

# 将class文件打包成jar包
echo "run command : jar cf0 rt_debug.jar *"
echo "package classes to rt_debug.jar"
jar cf0 rt_debug.jar *

# 指定新jar包的存放路径
rt_debug_endorsed_dir=/Library/Java/JavaVirtualMachines/jdk1.8.0_281.jdk/Contents/Home/jre/lib/endorsed
echo "target dir is : $rt_debug_endorsed_dir"

#如果文件夹不存在，创建文件夹
if [ ! -d $rt_debug_endorsed_dir ]; then
  # echo "passwd" | sudo -S shutdown -P now
  echo "654321" | sudo -S mkdir $rt_debug_endorsed_dir -P now
  echo "create target dir"
fi

# 复制jar包到指定路径
echo "copy rt_debug.jar to target dir: $rt_debug_endorsed_dir"
echo "654321" | sudo -S mv rt_debug.jar $rt_debug_endorsed_dir
echo ""
echo "====================操作结束==================="
endtime=`date +%s`
echo "本次运行时间： "$((endtime-starttime))"s"
