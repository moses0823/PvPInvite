PvPInvite
==========

Paper 26.2 PvP invitation plugin.

功能：

- /pvp 玩家
- /pvp accept
- /pvp deny
- /pvp cancel
- /pvp status
- /pvp：開啟模式選擇
- 多人組隊（1+v1+）：建立房間後分成 A、B 兩隊
- 房主可邀請玩家、查看兩隊、踢出非房主玩家並開戰
- 房間成員可查看兩隊列表與自己的隊伍，亦可退出房間
- 只有兩隊都有人且房主在房間內時才能開戰
- 多人戰鬥中只允許敵隊互相造成傷害，不會誤傷隊友

玩家沒有接受 PvP 邀請之前，雙方不能互相造成玩家傷害。

接受邀請後，只有這兩名玩家可以互相 PvP。

沒有距離限制。
沒有世界限制。
可以使用近戰。
可以使用弓箭等 Projectile。

邀請有效時間：60 秒。

需要：

- Paper 26.2
- Java 25

編譯：

Windows：

gradlew.bat build

完成後：

build/libs/PvPInvite-1.0.0.jar
