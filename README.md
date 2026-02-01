# java-udp-hole-punching-example
JavaでUDP hole punchingによるP2P通信を行うサンプルです。  
最も簡単な実装のため、Symmetric型のNATには未対応です。  
暗号化は未実装のため、機密情報は送信しないでください。  
マルチキャストが禁止されているなど、環境によっては正常に動作しない可能性があります。

# 動作イメージ(ローカル接続)
```mermaid
sequenceDiagram
    PC-A <<-->> PC-B : マルチキャストで自身の待ち受けポートを通知
    PC-A  <<->> PC-B : 相手のローカルIPとポート番号へ適当なパケットを送信(繰り返し)
    PC-A  <<->> PC-B : 通信が確立(以降もポートが閉じないよう定期的にパケットを送信)
```

# 動作イメージ(リモート接続)
```mermaid
sequenceDiagram
    PC-A           ->> STUN Server : リクエストを送信
    STUN Server    ->> PC-A        : IPマスカレードされたグローバルIPとポート番号を応答
    PC-B           ->> STUN Server : リクエストを送信
    STUN Server    ->> PC-B        : IPマスカレードされたグローバルIPとポート番号を応答
    PC-A        <<-->> PC-B        : 何らかの方法で相手のグローバルIPとポート番号を交換、手動で接続情報を入力
    PC-A         <<->> PC-B        : 相手のグローバルIPとポート番号へ適当なパケットを送信(繰り返し)
    PC-A         <<->> PC-B        : 通信が確立(以降もポートが閉じないよう定期的にパケットを送信)
```
