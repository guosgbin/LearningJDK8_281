JDK 源码阅读记录
## 介绍
本仓库代码是 `oracle  JDK 1.8.0_281` 的源码， 来自 `JDK` 安装目录里的 `src.zip` 包。
```
java version "1.8.0_281"
Java(TM) SE Runtime Environment (build 1.8.0_281-b09)
Java HotSpot(TM) 64-Bit Server VM (build 25.281-b09, mixed mode)
```

## 食用方式
假如编译无法通过，将  `${JAVA_HOME}/lib` 文件夹添加到 `IDEA` 的 `External Libraies` 下。

步骤：
`Project Structure -> Libaries -> 选择添加，选择文件夹，点击确认`

`srcipts` 文件夹下有几个 `jar` 包和脚本文件。
- `compile.bat`: `windows` 脚本。（未验证）
- `compile.sh`: 有点问题，不知道为什么会漏掉一些类。
- `targetCompile.sh`: 直接给 `IDE` 编译出来的 `class` 文件打成 `jar` 包复制到 `${JAVA_HOME}/jre/lib/endorsed` 文件夹下。


为了方便，我把下面这些路径的 `jar` 复制到 `srcipts` 文件夹下。
- `jce.jar` : `${JAVA_HOME}/jre/lib/jce.jar`
- `rt.jar` : `${JAVA_HOME}/jre/lib/rt.jar`
- `tools.jar` : `${JAVA_HOME}/lib/tools.jar`


## 参考
> https://www.cnblogs.com/vitoboy/p/15683218.html
> 
> https://blog.csdn.net/u010999809/article/details/102762142