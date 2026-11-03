# Termux 侧

`shortcuts/打开DSH.sh` 是 Termux:Widget 用的示例快捷方式：先同步文件、确保 DSH
服务在运行，然后拉起 Dterm（而不是浏览器）。

安装：

```bash
mkdir -p ~/.shortcuts
cp 打开DSH.sh ~/.shortcuts/打开DSH.sh
chmod 700 ~/.shortcuts/打开DSH.sh
```

脚本里用到的 `dsh-open --url`（打印当前带令牌的地址但不打开浏览器）和 `dsh-sync`
属于本机部署脚本，不在这个仓库里；没有它们的话，把脚本里那段换成自己的启动命令即可。
