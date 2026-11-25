# Pil Analizi

Telefonda yerel çalışan, son şarjdan bu yana hangi uygulamanın **pil üzerindeyken** ne kadar enerji harcadığını gösteren küçük bir Android uygulaması.

- Veriyi Android'in kendi `dumpsys batterystats` kaydından okur (üretici arayüzünden değil).
- **İnternet izni yoktur**: uygulama hiçbir ağa bağlanamaz.
- Servis, alıcı, alarm ya da iş içermez: **arka planda çalışmaz**, sadece açıkken okur.
- Her derlemede CI bu iki kuralı kontrol eder; ihlal varsa sürüm yayınlanmaz.

## Kurulum

1. [Releases](../../releases) sayfasından `PilAnalizi.apk` indirip kurun (ya da [Obtainium](https://github.com/ImranR98/Obtainium) ile bu repoyu takip edin).
2. Bilgisayardan bir kez izin verin:

```
adb shell pm grant tr.yerel.pilanaliz android.permission.DUMP
adb shell pm grant tr.yerel.pilanaliz android.permission.BATTERY_STATS
```

Güncellemeler aynı anahtarla imzalandığı için izinler korunur.

## Derleme

`main` dalına yapılan her push GitHub Actions'ta derlenir, imzalanır ve yeni bir sürüm olarak yayınlanır. Yerelde derlemek için `build.sh` dosyasına bakın.
