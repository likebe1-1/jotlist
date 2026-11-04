# 项目自有的 R8/ProGuard 规则。
#
# AGP 会合并本文件、androidx 依赖自带的规则，以及 src/main/keepRules/ 目录。
# 当前没有需要额外保留的类：数据模型只通过手写 JSON 读写，不依赖反射；
# manifest 中声明的组件由 AAPT 生成的规则自动保留。
# 这里保留空文件是为了让 app/build.gradle.kts 的 proguardFiles 引用成立
# （此前引用了不存在的文件，构建时会出现 "Supplied proguard configuration
# does not exist" 警告，容易被误以为规则已生效）。

# 需要时在此添加：
# -keep class com.manst.jotlist.SomeReflectiveModel { *; }
