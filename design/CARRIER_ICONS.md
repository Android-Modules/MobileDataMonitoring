# 运营商图标来源

两张图片直接从主人手机中已安装的官方应用 APK 提取，未重绘、未改变原图；仅用于标识对应运营商。图标及商标权利属于各自权利人。

| 模块资源 | 来源应用 | 版本 | APK 内资源 |
| --- | --- | --- | --- |
| ic_carrier_mobile.png | 中国移动江苏，com.jsmcc | 11.7.0 | res/mipmap-xxxhdpi-v4/ic_launcher_cmcc.png |
| ic_carrier_unicom.png | 中国联通，com.sinovatech.unicom.ui | 13.2 | res/mipmap-xxxhdpi-v4/icon3.png |

应用内按系统识别的运营商展示图标，不依靠号码前缀，不将图标视为官方查询已关联的证明。电信安装了 com.ct.client 时读取其应用图标，否则使用“电”标记；其他或未知运营商保留 SIM 标记。
