# Sesli Notlu Kamera – APK Alma

Bu klasör tam bir Android Studio / Gradle projesidir.

## Yol 1: Android Studio (en kolay)
1. Android Studio → File → Open → bu klasörü seçin. Gradle sync bitmesini bekleyin.
2. Build → Build Bundle(s) / APK(s) → Build APK(s)
3. APK şurada oluşur: app/build/outputs/apk/debug/app-debug.apk
   Telefona atıp açın (bilinmeyen kaynaklardan yüklemeye izin vermeniz gerekir).
   Ya da telefonu USB ile bağlayıp ▶ Run'a basın.

## Yol 2: Bilgisayara hiçbir şey kurmadan (GitHub Actions)
1. github.com'da ücretsiz bir hesapla yeni bir depo (repository) oluşturun.
2. Bu klasörün içeriğini (.github klasörü dahil) depoya yükleyin.
3. Depoda "Actions" sekmesi → "APK Derle" → birkaç dakika sonra bitince
   sayfanın altındaki "Artifacts" bölümünden CameraApp-debug-apk dosyasını indirin.
   İçinden app-debug.apk çıkar.

## Kullanım
- Deklanşör → fotoğraf çekilir, kırmızı mikrofon çıkar → dokunup anlatın → metin EXIF'e yazılır.
- Sol alttaki küçük resim / galeri düğmesi → açıklamalı fotoğraflar; birine dokununca metin okunur.

## Notlar
- Konuşma tanıma: Google servisi, Türkçe için internet veya indirilmiş çevrimdışı model gerekir.
- Sesli okuma: cihazda Türkçe TTS verisi yüklü olmalı.
- HDR düğmesi sadece destekleyen cihazlarda görünür.
