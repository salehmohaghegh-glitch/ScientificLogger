# ScientificLogger

ScientificLogger یک اپلیکیشن اندروید نوشته‌شده با Kotlin برای ثبت و نمایش داده‌های تجربی و علمی (لاگ‌برداری، فیلترگذاری، رسم نمودار).

ویژگی‌ها
- ثبت و ذخیره داده‌ها (فرمت محلی)
- نمایش نمودارها و گراف‌ها برای تحلیل زمان-مقدار
- فیلتر کالمن (Kalman filter) برای هموارسازی نویز
- صفحه‌ی راهنما و تنظیمات ساده

نیازمندی‌ها
- Android Studio (نسخه‌ی اخیر)
- JDK 11+
- Gradle (wrapper داخل مخزن استفاده می‌شود)

راه‌اندازی و اجرا
1. مخزن را کلون کنید:
   git clone https://github.com/salehmohaghegh-glitch/ScientificLogger.git
   cd ScientificLogger

2. با Android Studio باز کنید
   - گزینه‌ی "Open an existing Android Studio project" را انتخاب کنید.
   - منتظر باشید Gradle sync کامل شود.
   - یک دستگاه مجازی (AVD) یا دستگاه فیزیکی متصل کنید و Run را بزنید.

یا از خط فرمان:
   ./gradlew assembleDebug        # تولید فایل‌های apk
   ./gradlew installDebug         # نصب روی دستگاه متصل (در صورت وجود)

ساختار پروژه (خلاصه)
- app/src/main/java/com/saleh/scientificlogger
  - MainActivity.kt, MainViewModel.kt — نقطه‌ی ورود و منطق ViewModel
  - KalmanFilter.kt — پیاده‌سازی فیلتر کالمن
  - ChartScreen.kt, GraphsScreen.kt — صفحات نمایش نمودار
  - SettingsScreen.kt, HelpScreen.kt — صفحات تنظیمات و راهنما
  - AppNavigation.kt, Screens.kt — ناوبری داخلی برنامه

نکات توسعه
- منطق UI و نمایش نمودارها در بسته‌ی `ui` و فایل‌های Chart/Graphs قرار دارد.
- وابستگی‌های دقیق در فایل‌های build.gradle.kts (در ریشه و در app/) مشخص شده‌اند — برای افزودن یا آپدیت لایبرری‌ها آن فایل‌ها را ویرایش کنید.

حفظ محرمانگی و پاک‌سازی
- هر کلیدی یا اطلاعات محرمانه را از مخزن حذف کنید (اگر در پوشه Mykey یا فایل مشابهی وجود دارد).
- فایل‌های پیکربندی IDE (مثلاً .idea/) را نادیده بگیرید یا از git حذف کنید و در .gitignore قرار دهید.

لایسنس
- اگر مایلید پروژه متن باز باشد، یک فایل LICENSE (مثلاً MIT) اضافه کنید.

تماس
- Issues و pull requests خوش‌آمدند.
