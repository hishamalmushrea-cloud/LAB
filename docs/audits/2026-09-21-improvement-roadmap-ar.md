# خارطة تحسين وتطوير Code on the Go

> التاريخ: 2026-09-21  
> النطاق: مقترحات عملية مبنية على فحص اللقطة المستوردة من `appdevforall/CodeOnTheGo:stage`.  
> الهدف: تحويل النسخة المنقولة من لقطة مصدر ناجحة إلى منتج مستقل، آمن، قابل للبناء والصيانة والتوسع.

## 1. الرؤية المقترحة

أفضل اتجاه للمشروع ليس تقليد Android Studio على شاشة صغيرة حرفيًا، بل أن يصبح:

> **بيئة تطوير Android محمولة، offline-first، آمنة وقابلة للتوسعة، تضبط نفسها تلقائيًا حسب موارد الهاتف، وتخدم التعليم والتطوير السريع في البيئات محدودة الاتصال.**

ينبغي الحفاظ على أربعة مبادئ:

1. **النواة صغيرة وموثوقة**، والميزات الثقيلة أو الاختيارية إضافات.
2. **العمل المحلي هو الافتراضي**؛ الشبكة وAI والـtelemetry خيارات صريحة.
3. **الهاتف ليس حاسوبًا مكتبيًا**؛ الحرارة والبطارية والذاكرة والتخزين عناصر تصميم أساسية.
4. **كل release قابل لإعادة البناء والتتبع** دون اعتماد غير موثق على بنية مؤسسة واحدة.

---

## 2. الأولويات التنفيذية المختصرة

| الأولوية | العمل | الأثر | الجهد التقريبي |
|---|---|---:|---:|
| P0 | تقوية حدود الثقة في نظام الإضافات | حرج | كبير |
| P0 | إغلاق/حماية endpoints الخادم المحلي | حرج | صغير–متوسط |
| P0 | جعل الأصول الخارجية موقعة وقابلة لإعادة البناء | حرج | متوسط–كبير |
| P0 | فصل CI للتحقق عن CI للنشر | عالٍ | متوسط |
| P0 | جرد telemetry وتصحيح الإفصاح والتنقيح | عالٍ | متوسط |
| P1 | خطة انتقال تدريجية من target SDK 28 | عالٍ | كبير |
| P1 | إصلاح Device E2E وبناء matrix ثابتة | عالٍ | متوسط |
| P1 | تنظيف بقايا AI/llama/Compose/Layout Editor | متوسط–عالٍ | صغير–متوسط |
| P1 | Plugin install ذري مع rollback وتوافق نسخ | عالٍ | متوسط |
| P1 | شاشة Environment Doctor وصيانة التخزين | عالٍ | متوسط |
| P2 | تحويل نظام ضبط Gradle إلى Build Lab ذكي | مميز جدًا | متوسط–كبير |
| P2 | متجر إضافات موثوق وتحديثات موقعة | مميز جدًا | كبير |
| P2 | محدث تطبيق موقّع وقنوات Stable/Beta | عالٍ | متوسط |
| P2 | إكمال العربية وRTL وإمكانية الوصول | عالٍ للمستخدم العربي | متوسط |
| P2 | تحسين التعافي من الانهيار والجلسات والنسخ الاحتياطي | عالٍ | متوسط |
| P3 | وضع تعليمي offline وحزم مناهج | استراتيجي | كبير |
| P3 | AI محلي/سحابي عبر plugins فقط مع privacy guard | استراتيجي | كبير |

---

# 3. P0 — ما يجب إصلاحه قبل إضافة مزايا كبيرة

## 3.1 إعادة تصميم أمان الإضافات

### المشكلة

الإضافة تُحمّل داخل عملية التطبيق عبر `DexClassLoader`، ويمكنها تحميل native libraries.
صلاحيات Plugin API تقيد الخدمات الرسمية، لكنها لا تعزل bytecode نفسه. التحقق من توقيع
إضافة جديدة تحذيري، وليس شرطًا مانعًا. كما أن حقول manifest مثل معرف الإضافة تُستخدم
في أسماء ومسارات متعددة وتحتاج سياسة صياغة واحتواء مركزية قبل أي عملية ملفات.

### التحسين المطلوب

1. تعريف معرف canonical للإضافة مثل:

   ```text
   [a-z][a-z0-9]*(\.[a-z][a-z0-9_-]*)+
   ```

   مع حد طول، ومنع separators وdot segments وأحرف التحكم.

2. التحقق من المعرف مرة واحدة قبل:
   - النسخ إلى مجلد plugins.
   - إنشاء مجلد data/native/icons.
   - استخراج templates أو documentation.
   - الحذف والتنظيف.

3. استخدام resolver مركزي يثبت أن كل مسار ناتج داخل مجلد الإضافة، بدل إنشاء
   `File(parent, pluginId)` في مواضع متعددة.

4. جعل التوقيع إلزاميًا في الوضع العادي:
   - Trusted publisher catalog.
   - بصمة ناشر معروضة للمستخدم.
   - TOFU اختياري للمطورين.
   - وضع Developer Mode منفصل يسمح بإضافة غير موثوقة مع تحذير قوي.

5. توضيح الحقيقة للمستخدم: صلاحيات الإضافة capabilities للخدمات وليست sandbox.

6. دراسة تشغيل الإضافات عالية الخطورة في process منفصلة، خصوصًا:
   - AI/network plugins.
   - plugins ذات `system.commands`.
   - plugins ذات `native.code`.

7. وضع quotas:
   - الحجم الأقصى لملف `.cgp`.
   - الحجم الإجمالي بعد الاستخراج.
   - عدد entries.
   - حجم native libraries/templates/icons.
   - وقت initialization.

8. إضافة static scanner قبل التثبيت يعرض:
   - permissions.
   - native code.
   - debug flag.
   - provenance revision.
   - certificate fingerprint.
   - حجم الملفات وما ستضيفه الإضافة.

### معيار القبول

- لا يُستخدم أي حقل manifest في مسار قبل التحقق المركزي.
- لا تُحمّل إضافة بلا قرار ثقة صريح.
- اختبارات adversarial للمعرفات والمسارات والأحجام والتوقيع.
- توثيق واضح بأن العزل الكامل غير متحقق ما دامت الإضافة داخل العملية.

---

## 3.2 جعل تثبيت وتحديث الإضافات ذريًا

### المشكلة

مسار الاستبدال الحالي يزيل النسخة القديمة ثم ينسخ/يحمّل الجديدة. إذا فشل النسخ أو
التحميل، قد يخسر المستخدم النسخة العاملة. حقول `minIdeVersion`, `maxIdeVersion` و
`dependencies` تُقرأ، لكن لا يظهر إنفاذ فعلي شامل لها قبل التحميل.

### المقترح

1. نسخ الإضافة الجديدة إلى staging directory.
2. فحص التوقيع والـmanifest والتوافق والاعتمادات والأحجام.
3. تحميل probe دون تفعيل contributions إن أمكن.
4. نقل ذري `rename` إلى المسار النهائي.
5. حفظ النسخة السابقة حتى نجاح activation.
6. rollback تلقائي عند الفشل.
7. إنفاذ:
   - أقل/أعلى نسخة IDE.
   - نسخة Plugin API/ABI.
   - تبعيات الإضافات وترتيب التحميل.
   - اكتشاف dependency cycles.
8. حفظ transaction journal للتعافي بعد process death.

### معيار القبول

فشل أي خطوة لا يحذف النسخة السابقة ولا يترك ملفًا نصف مثبت.

---

## 3.3 إغلاق الخادم المحلي التشخيصي

### المشكلة

الخادم على `localhost:6174` يبدأ مع `MainActivity` ويقدم endpoints بلا مصادقة، ومنها
بيانات المشاريع الحديثة ومساراتها. loopback في Android ليس حد sandbox بين التطبيقات.

### المقترح بالترتيب

1. إزالة endpoints التشخيصية من release فورًا.
2. استخدام `DocumentationRequestInterceptor` داخل WebView بدل socket للمحتوى الداخلي.
3. إذا بقي socket:
   - port عشوائي.
   - nonce قوي لكل session في كل URL.
   - رفض Host/Origin غير المتوقع.
   - مدة حياة قصيرة.
   - عدم عرض مسارات ملفات أو قواعد بيانات.
4. إضافة instrumentation test من تطبيق ثانٍ يحاول الوصول إلى endpoint.
5. مراجعة WebView settings وJavaScript bridges وCSP.

### معيار القبول

لا يستطيع تطبيق آخر على الجهاز قراءة اسم مشروع أو مساره أو بيانات تشخيصية.

---

## 3.4 سلسلة توريد الأصول والبناء القابل لإعادة الإنتاج

### المشكلة

SDK وGradle وbootstrap وMaven cache والتوثيق تأتي من بنية خارج Git. MD5 يفحص التلف،
لكنه ليس provenance مستقلًا. توجد placeholders قديمة وصفرية الحجم.

### المقترح

1. إنشاء `assets-manifest.json` متعقب يحتوي لكل أصل:
   - الاسم والمنصة/ABI.
   - النسخة.
   - الحجم.
   - SHA-256 أو SHA-512.
   - URL ثابت غير mutable.
   - تاريخ ومصدر البناء.
   - license/SBOM reference.

2. توقيع manifest باستخدام Sigstore/Cosign أو مفتاح release منفصل.
3. نشر الأصول كـGitHub Release/OCI artifacts مع retention واضح، لا مسارات `latest`.
4. توفير سكربت reproducible لبناء كل أصل من المصدر.
5. تنزيل ذري وقابل للاستكمال، مع حذف الملف المؤقت عند فشل checksum.
6. mirror ثانٍ وخيار offline bundle موثق.
7. إزالة placeholders المضللة أو تحويلها إلى manifest pointers واضحة.
8. بناء APK مرتين في بيئتين ومقارنة النتائج بعد ضبط timestamps/versionCode.
9. إصدار SBOM للتطبيق وللأصول الأصلية.

### معيار القبول

يمكن لمطور جديد بناء نفس release من مستندات عامة دون وصول SSH إلى خادم المؤسسة.

---

## 3.5 الخصوصية والـtelemetry

### المشكلة

الموافقة موجودة، وهي نقطة جيدة. لكن وصف البيانات بأنها anonymous يحتاج مواءمة مع
`ANDROID_ID` ومعلومات الجهاز والسجلات وmetrics المرفقة.

### المقترح

1. Data inventory مولد من الكود: event، fields، الغرض، retention، الوجهة.
2. استبدال Android ID بمعرف تثبيت عشوائي قابل للمسح، أو عدم استخدام معرف ثابت.
3. redaction مركزي للسجلات:
   - paths.
   - usernames.
   - project names.
   - repository URLs/tokens.
   - source snippets.
4. فصل الموافقات:
   - crash reports.
   - anonymous usage metrics.
   - diagnostic attachments.
5. شاشة Preview Data حقيقية تعرض payload قبل الإرسال.
6. زر Export/Delete telemetry identity.
7. سياسة retention وحذف موثقة.
8. اختبارات تؤكد أن `DECLINED` لا ينشئ Firebase/Sentry ولا اتصالًا شبكيًا.
9. وضع Privacy/Offline ظاهر دائمًا وليس في onboarding فقط.

---

# 4. P1 — الاستقرار وقابلية الصيانة

## 4.1 الانتقال من target SDK 28 دون كسر المنتج

لا ينبغي رفع target إلى 36 دفعة واحدة؛ المشروع يعتمد على سلوكيات ملفات وتثبيت وخدمات
قديمة. الخطة الأفضل:

1. كتابة compatibility matrix لكل API من 28 إلى 36.
2. جرد كل صلاحية وسببها ومسار بديل لها.
3. نقل المشاريع تدريجيًا إلى:
   - SAF tree grants.
   - workspace داخلي مع export/import.
   - MediaStore/DocumentsProvider حيث يلزم.
4. تحديث foreground services وnotifications وpackage install flows.
5. اختبار Android 9، 11، 13، 14، 15، 16 على أجهزة فعلية/مختبر.
6. رفع target على مراحل مع feature flags وcanary releases.
7. نشر وثيقة تشرح لماذا تحتاج بيئة تطوير صلاحيات أكثر من تطبيق عادي.

هدف النجاح: target حديث دون التضحية بإمكانية بناء وإدارة المشاريع.

---

## 4.2 إصلاح منظومة الاختبار على الأجهزة

### المطلوب

- إصلاح فشل `Setup Android SDK` في Firebase Test Lab.
- فصل فشل infrastructure عن فشل test في التقارير.
- Smoke journey ثابت:
  1. تثبيت التطبيق.
  2. رفض telemetry.
  3. إكمال onboarding.
  4. تثبيت toolchain.
  5. إنشاء مشروع Kotlin.
  6. sync.
  7. تعديل ملف.
  8. build APK.
  9. install/run.
  10. قراءة logs.
  11. breakpoint بسيط.

- Journey ثانٍ لمشروع Groovy وآخر متعدد الوحدات.
- Matrix لكل ABI وإصدارات Android المهمة.
- اختبارات upgrade من release سابق مع بقاء المشاريع والإضافات.
- اختبار low storage، انقطاع التنزيل، process death، thermal throttling وOOM.
- اختبار malicious `.cgp`, `.cgt`, ZIP, deep link وDocumentsProvider.

### بوابات الدمج المقترحة

- PR سريع: formatting + unit + selected Robolectric + config check.
- Nightly: كامل JVM + debug APK.
- أسبوعي: device E2E + release reproducibility + security corpus.

---

## 4.3 Environment Doctor

إضافة شاشة تشخيص ذات قيمة عالية تعرض:

- ABI وإصدار Android.
- الذاكرة المتاحة والإجمالية.
- الحرارة والبطارية.
- المساحة المطلوبة والمتاحة.
- نسخ JDK/SDK/Gradle/AGP/Kotlin.
- سلامة كل أصل وchecksum.
- صلاحيات الملفات والتثبيت والـoverlay.
- حالة Gradle daemon/tooling server.
- المرايا والشبكة وTLS.
- زر إصلاح لكل مشكلة قابلة للإصلاح.
- Export report بعد تنقيح البيانات الحساسة.

هذه الشاشة ستقلل البلاغات الغامضة من نوع “البناء لا يعمل”.

---

## 4.4 إدارة التخزين والكاش

بيئة التطوير تستهلك مساحة كبيرة. المطلوب:

- لوحة توزيع المساحة: SDK، Gradle، Maven، builds، logs، heap dumps، plugins.
- تنظيف آمن مع تقدير المساحة التي ستتحرر.
- عدم حذف caches التي يحتاجها العمل offline دون تحذير.
- quotas للسجلات وheap dumps وplugin data.
- LRU للإصدارات القديمة من Gradle/SDK.
- preflight قبل build يمنع البدء إذا كانت المساحة لا تكفي.
- backup/export للمشاريع والإعدادات وقائمة الإضافات.

---

## 4.5 تنظيف الانحراف المعماري والتوثيقي

حزمة تنظيف واحدة ينبغي أن تشمل:

- حذف include القديم لـ`:compose-preview` بعد التأكد.
- تحديث `ARCHITECTURE.md` لتمييز core عن external plugins.
- أرشفة أو تحديث `E2E_TESTING_REPORT.md`.
- إزالة scripts/stub/version الخاصة بـllama إذا لم تعد للنواة.
- إزالة نصوص AI القديمة أو نقلها إلى الإضافات.
- تحديث CODEOWNERS ومسارات LayoutEditor.
- تقرير مصير `project-serial` و`project-serialization`.
- إزالة مشروع `vectormaster/` المكرر بعد إثبات أن نسخة `xml-inflater` هي المستخدمة.
- تحديث مراجع keystore plugin.
- توليد module inventory آليًا في CI لمنع عودة drift.

### قاعدة مقترحة

أي feature تُستخرج إلى plugin يجب أن تملك checklist آليًا يبحث عن:

- settings includes.
- resources.
- docs.
- tests/reports.
- scripts.
- CI tasks.
- CODEOWNERS.
- dependency aliases.

---

## 4.6 تقليل مركزية `:app`

`app` يعتمد مباشرة على عشرات الوحدات. التحسين التدريجي:

1. تعريف feature boundaries واضحة.
2. نقل wiring إلى feature-specific composition modules.
3. منع الاعتماد العكسي على `app` عبر CI rule.
4. استخدام interfaces صغيرة بدل service locators/global singletons حيث يمكن.
5. توليد dependency graph ورفض cycles.
6. قياس configuration وincremental build impact لكل PR.
7. عدم محاولة “إعادة كتابة كاملة”؛ الفصل يتم feature-by-feature.

---

# 5. P2 — تحسين المنتج وتجربة المستخدم

## 5.1 تحويل Build Tuner إلى ميزة رئيسية: Build Lab

المشروع يملك أصلًا ميزة مميزة غير معتادة: يختار استراتيجية Gradle حسب RAM وCPU
والحرارة، ويراقب ذاكرة IDE وtooling server وGradle daemon إضافة إلى الطاقة والشبكة.
هذه ليست مجرد تفاصيل داخلية؛ يمكن تحويلها إلى نقطة تفوق تسويقية.

### التطوير المقترح

- Dashboard قبل/بعد البناء يعرض:
  - زمن configuration/compile/package/install.
  - peak memory لكل process.
  - حرارة الجهاز.
  - استهلاك تقريبي للطاقة.
  - cache hit/miss.
  - سبب اختيار LowMemory/Balanced/HighPerformance/ThermalSafe.

- توصيات قابلة للتنفيذ:
  - خفض workers.
  - تفعيل/تعطيل configuration cache.
  - تنظيف daemon متسرب.
  - تأجيل build عند حرارة خطرة.
  - اقتراح charger أو إطفاء الشاشة للبناء الطويل.

- تعلم محلي غير معرف للمستخدم:
  - حفظ نتائج builds السابقة على نفس الجهاز/المشروع.
  - اختيار profile الأفضل وفق القياس الحقيقي لا RAM فقط.
  - زر “الأسرع”، “الأبرد”، “الأقل استهلاكًا”، “المتوازن”.

- Benchmark قابل للمشاركة بعد تنقيح البيانات.

هذا اتجاه مميز جدًا ولا يملكه معظم محررات الهاتف.

---

## 5.2 متجر إضافات موثوق

بدل رابط catalog فقط:

- فهرس موقّع داخل التطبيق.
- قنوات Stable/Beta/Developer.
- ناشر وبصمة شهادة ومصدر وترخيص واضح.
- compatibility قبل التنزيل.
- تحديث تلقائي اختياري مع rollback.
- تقييمات لا تكون معيار الثقة الوحيد.
- badges:
  - Verified publisher.
  - Reproducible build.
  - No network.
  - Local-only AI.
  - Native code.
- قائمة permissions وتغيرها بين نسختين.
- إيقاف update إذا تغير signer أو زادت صلاحياته دون موافقة.
- quarantine تلقائي عند crash loop.

---

## 5.3 تحسين onboarding

- حساب مسبق للحجم والوقت حسب الاتصال والجهاز.
- اختيار Minimal أو Full toolchain.
- تنزيل في الخلفية مع resume.
- شرح سبب كل صلاحية وقت الحاجة لا دفعة واحدة.
- مشروع Tutorial يبني خلال دقائق بعد الإعداد.
- اختبار تلقائي “Hello World” يثبت أن SDK وGradle يعملان.
- مسار خاص للمستخدم المبتدئ ومسار سريع للخبير.

---

## 5.4 نسخة Lite وحزم On-demand

لتقليل حجم APK والتخزين:

- Core IDE APK أخف.
- تنزيل Toolchain Pack حسب ABI.
- NDK/CMake حزمة اختيارية.
- Offline docs حزمة اختيارية وقابلة للتحديث.
- AI models لا تدخل core إطلاقًا.
- إبقاء Full Offline Bundle لمن يحتاج تثبيتًا دون إنترنت.

ينبغي أن تكون الحزم كلها موقعة ومثبتة بإصدارات، مع migration وrollback.

---

## 5.5 محدث تطبيق موقّع

لم يظهر في النواة مسار واضح ومتكامل لفحص تحديث التطبيق. يلزم محدث مستقل عن متجر
الإضافات، خصوصًا إذا كان التوزيع خارج Google Play:

- feed موقّع يحتوي النسخة و`versionCode` والـABI والحجم وSHA-256.
- قنوات Stable/Beta/Nightly باختيار المستخدم.
- release notes وتحذير مسبق عند migration غير قابلة للرجوع.
- تنزيل ذري قابل للاستكمال، ثم تحقق التوقيع قبل طلب التثبيت.
- منع downgrade وتبدل signer، مع مسار rollback مدروس للإصدارات التجريبية.
- عدم تنزيل تحديث تلقائيًا على البيانات الخلوية دون موافقة.
- دعم Play/GitHub/متجر مستقل عبر providers بدل hard-code لجهة واحدة.
- عدم استخدام “آخر ملف” mutable أو الثقة في checksum آتٍ من المصدر نفسه بلا توقيع.

---

## 5.6 دورة تشغيل أسرع دون ادعاء Hot Reload

`QuickBuild` مسار بناء سريع، لكنه ليس دليلًا على hot code swap حقيقي. التحسين الآمن هو
تقليل زمن edit-build-install-run أولًا:

- تصنيف التغيير: resources فقط، Kotlin/Java، manifest، Gradle أو dependency.
- تنفيذ أقل مجموعة مهام Gradle لازمة لكل نوع.
- عدم إعادة تثبيت APK إن لم يتغير الناتج.
- إعادة فتح Activity/route السابقة بعد التثبيت.
- عرض سبب سقوط المسار السريع إلى full build.
- قياس زمن كل مرحلة وحفظ baseline لكل مشروع.
- بحث Apply Changes/hot swap لاحقًا كميزة تجريبية ذات matrix توافق معلنة، لا كوعد عام.

---

## 5.7 التعافي والجلسات والنسخ الاحتياطي

المشروع يحذر من الملفات غير المحفوظة ويحفظ session state، ويمكن تطوير ذلك إلى:

- local history لكل ملف.
- snapshots قبل refactor/build/plugin action.
- crash recovery واضح عند العودة.
- autosave journal لا يستبدل الملف الأصلي حتى commit آمن.
- استعادة التبويبات والمؤشرات والـbreakpoints.
- export مشفر للإعدادات وGit hosts وقائمة plugins دون tokens افتراضيًا.
- project backup مع استبعاد build/caches.

---

## 5.8 Git أكثر ملاءمة للهاتف

- staging جزئي hunk-by-hunk.
- conflict editor بصري.
- commit graph خفيف.
- SSH keys عبر Android Keystore إن أمكن.
- credential scopes متعددة لكل host/repository.
- token expiry diagnostics.
- signed commits اختياريًا.
- clone sparse/partial للمشاريع الكبيرة.
- حماية من إدخال secrets في commit عبر opt-in scanner محلي.

---

## 5.9 إدارة الاعتمادات داخل المشروع

ميزة ذات قيمة للمبتدئ والمتقدم:

- محرر `libs.versions.toml` بصري.
- بحث Maven وإضافة dependency.
- عرض سبب وجود dependency وشجرة transitives.
- كشف conflicts ونسخ قديمة.
- Gradle compatibility hints.
- offline catalog من local Maven cache.
- فحص OSV اختياري محلي/شبكي مع شرح قابل للتنفيذ.

---

# 6. اللغة العربية وإمكانية الوصول

## 6.1 العربية

- إكمال الفجوة بين نحو 450 نصًا عربيًا و1,449 نصًا أساسيًا.
- منع fallback الإنجليزي غير المقصود في CI.
- glossary ثابت للمصطلحات التقنية.
- screenshots عربية لكل الشاشات الرئيسية.
- اختبار RTL آلي ويدوي للـeditor، drawer، dialogs وterminal.
- عدم قلب مواضع الكود أو أرقام الأسطر بطريقة تضر LTR content.
- توثيق عربي offline للمبتدئين.

## 6.2 Accessibility

- Content descriptions كاملة.
- touch targets لا تقل عن 48dp.
- دعم TalkBack والتنقل بلوحة المفاتيح.
- contrast checks للثيمات.
- إعدادات حجم النص مستقلة للمحرر والواجهة والterminal.
- عدم الاعتماد على اللون وحده للأخطاء والـGit diff.
- وضع low-motion وتقليل animations.

---

# 7. تطوير أدوات اللغات والمحرر

## Java/Kotlin/XML

- persistent incremental index مع versioning وفساد قابل للإصلاح.
- cancellation صارم عند إغلاق المشروع أو تغيير الملف.
- توحيد feature parity بين Java وKotlin.
- قياس latency للإكمال والتشخيص محليًا.
- تعطيل التحليل الثقيل تلقائيًا تحت ضغط الذاكرة.
- دعم أفضل للمشروعات متعددة الوحدات وconvention plugins/version catalogs.
- Workspace symbol search موحد.
- refactor preview قبل الكتابة.
- semantic rename مع rollback.
- formatter profiles per project.

## المحرر

- minimap اختياري للأجهزة الكبيرة.
- multi-cursor مدروس للمس.
- command palette.
- split editor على الأجهزة اللوحية.
- keyboard-first mode.
- breadcrumb للمسار والكلاس والدالة.
- compare editor لملفين أو Git diff.
- إدارة الملفات الكبيرة/binary بحدود واضحة.

---

# 8. AI — كيف نطوره دون الإضرار بالنواة؟

لا أوصي بإعادة AI إلى core. الاتجاه الصحيح هو plugins مع contract ثابت.

## المطلوب

- `ai-core` plugin يملك orchestration فقط.
- backends مستقلة: local, Gemini, OpenAI, MCP وغيرها.
- permission مستقل لإرسال source خارج الجهاز.
- شاشة تعرض بالضبط الملفات/المقاطع التي ستُرسل.
- secrets في Android Keystore، لا preferences عادية.
- per-project allow/deny rules و`.aiignore`.
- local-first صغير للمهام البسيطة.
- tool calls تمر عبر capability layer قابلة للمراجعة والموافقة.
- patch preview واختبارات قبل تطبيق تعديل AI.
- audit log محلي لكل ملف/أمر غيّره agent.
- منع agent من تشغيل shell أو تعديل signing/CI دون موافقة منفصلة.
- budgets وحدود وقت/token/network.

## ميزات AI المفيدة فعلًا على الهاتف

- شرح الخطأ الحالي.
- توليد test صغير.
- إصلاح imports/Gradle error.
- تلخيص diff.
- تحويل وصف واجهة إلى XML/Compose كـpreview patch.
- بحث دلالي محلي.
- توثيق offline مساعد.

---

# 9. وضع التعليم والعمل في الاتصال الضعيف

هذا مجال يمكن أن يميز المنتج عالميًا:

- مسارات تعلم offline بالعربية والإنجليزية.
- مشاريع صغيرة تتدرج من Hello World إلى Room/API.
- checker محلي يختبر خطوة الطالب دون خادم.
- teacher pack بصيغة موقعة.
- مشاركة القوالب عبر QR أو ملف محلي.
- وضع classroom LAN دون إنترنت كإضافة.
- شرح أخطاء Gradle بلغة بسيطة.
- جهاز افتراضي غير مطلوب: تشغيل النتيجة على الجهاز نفسه.
- وضع “اقتصادي” للهواتف ذات 3–4GB RAM.

---

# 10. أفكار مميزة إضافية

## 10.1 مشروع Doctor + Repair

ليس مجرد تشخيص؛ يستطيع إعادة تنزيل أصل تالف، إصلاح wrapper، تنظيف daemon، إعادة بناء
index، واختبار التوقيع ومسارات SDK.

## 10.2 QR Project Links

البنية الحالية للروابط العميقة يمكن توسيعها بأمان إلى QR يفتح مشروعًا/ملفًا/سطرًا
محليًا، مع عدم تضمين المسار الكامل أو أسرار. مفيد للتعليم والمراجعة الجماعية.

## 10.3 Local Build Replay

حفظ ملخص build graph والأزمنة والأخطاء، ثم مقارنة buildين لمعرفة سبب البطء أو الفشل.

## 10.4 Safe Mode

عند crash loop:

- تعطيل جميع plugins مؤقتًا.
- فتح آخر مشروع دون sync.
- تعطيل LSP أو profiler اختياريًا.
- تصدير تقرير منقح.

المشروع لديه crash tracking للإضافات؛ المطلوب تحويله إلى تجربة استرداد كاملة.

## 10.5 Compatibility Lab

تشغيل sample projects عبر matrix من AGP/Gradle/Kotlin المدعومة، ونشر جدول توافق آلي
بدل الاعتماد على constants ووثائق قد تنحرف.

## 10.6 Optional Remote Builder

ميزة اختيارية وليست بديلًا عن offline build:

- إرسال source مشفر إلى runner يملكه المستخدم.
- لا تستخدم افتراضيًا.
- مفيدة للمشاريع الكبيرة أو i/o/NDK الثقيل.
- يجب أن تكون protocol/plugin منفصلًا مع threat model واضح.

---

# 11. القياسات المقترحة للنجاح

ينبغي قياس ما يلي محليًا، وإرساله فقط عند الموافقة:

## الاعتمادية

- نسبة onboarding المكتمل.
- نسبة نجاح asset installation.
- نسبة sync الناجح.
- نسبة build الناجح حسب نوع المشروع.
- crash-free sessions.
- rollback success للإضافات.

## الأداء

- وقت first build وincremental build.
- peak RAM لكل process.
- عدد OOM/LMK.
- وقت autocomplete p50/p95.
- حرارة الجهاز عند نهاية build.
- حجم التخزين قبل/بعد cache cleanup.

## المنتج

- الوقت من التثبيت إلى أول APK ناجح.
- عدد المشاريع النشطة محليًا.
- استخدام plugins مع تمييز core/plugin.
- نسبة النصوص المترجمة والمختبرة.

ينبغي ألا تصبح metrics سببًا لجمع مسارات أو أسماء مشاريع أو source content.

---

# 12. أشياء لا أوصي بها

1. **إعادة كل الإضافات إلى monolith**؛ سيعيد تضخم APK والنواة.
2. **رفع target SDK مباشرة دون migration**؛ قد يكسر التخزين وTermux والتثبيت.
3. **تشغيل workflows المستوردة بمجرد إضافة secrets**؛ يجب أولًا تغيير كل وجهات النشر.
4. **اعتبار plugin permissions sandbox** قبل عزل العملية أو إنفاذ bytecode boundary.
5. **إعادة كتابة المشروع كاملًا بـCompose/Kotlin**؛ المخاطر أعلى من الفائدة.
6. **دمج AI في كل تدفق**؛ يجب أن يبقى اختياريًا وقابلًا للإزالة.
7. **الاعتماد على analytics لتحسين الأداء فقط**؛ القياس المحلي يجب أن يعمل دون موافقة شبكة.
8. **حذف caches تلقائيًا بلا شرح**؛ العمل offline قد يتوقف بعدها.
9. **دعم نسخ toolchain كثيرة بلا matrix**؛ الأفضل نطاق أصغر مثبت الاختبار.

---

# 13. خطة زمنية مقترحة

## أول 30 يومًا — تثبيت الأساس

- تثبيت build نظيف مستقل في CI غير ناشر.
- إصلاح instrumentation infrastructure.
- إغلاق diagnostics endpoints في release.
- strict plugin ID/path validation.
- بدء plugin transaction/rollback.
- asset manifest مع SHA-256.
- تنظيف docs/includes الأوضح.
- data inventory للـtelemetry.

## 31–90 يومًا — الاعتمادية

- signed asset pipeline.
- Environment Doctor.
- plugin compatibility/dependency enforcement.
- storage dashboard.
- E2E journeys على جهازين/APIين على الأقل.
- target SDK compatibility audit.
- Safe Mode واسترداد sessions.
- إكمال أهم النصوص العربية وRTL smoke test.

## 3–6 أشهر — التميز

- trusted plugin catalog.
- Build Lab وprofiles قابلة للاختيار.
- Lite/Full toolchain channels.
- signed app updater وقنوات Stable/Beta.
- تحسين دورة QuickBuild مع قياس edit-to-run.
- local history وbackup/export.
- dependency manager.
- توسعة device matrix وreproducible release check.

## 6–12 شهرًا — النمو

- target SDK حديث بعد migration.
- Education Mode وحزم offline.
- AI plugins مع privacy/capability controls.
- optional collaboration/remote builder plugins.
- compatibility dashboard عام للإضافات والمشاريع.

---

# 14. أول Sprint أوصي بتنفيذه

مدة مقترحة: أسبوعان.

1. إضافة validator مركزي لمعرف الإضافة واختبارات adversarial.
2. حماية كل مسار مشتق من manifest باحتواء canonical.
3. منع unsigned plugins إلا في Developer Mode.
4. تعطيل `/pr/pr` وبقية diagnostics في release.
5. إضافة `assets-manifest.json` أولي بـSHA-256.
6. حذف `:compose-preview` القديم وتحديث architecture/core-vs-plugin docs.
7. إصلاح Firebase Test Lab setup أو جعل failure واضحًا كبنية تحتية.
8. بناء Environment Doctor MVP يعرض النسخ والمساحة وchecksums.
9. وضع baseline للأداء: first build، incremental، RAM، حرارة.
10. إكمال أكثر 100 نص عربي ظهورًا واختبار RTL للشاشات الأساسية.

## Definition of Done

- اختبارات unit/security جديدة ناجحة.
- v7 وv8 debug build ناجحان في CI نظيف.
- لا job نشر يعمل دون opt-in صريح.
- لا endpoint release يكشف مسارات المشاريع.
- لا يُقبل plugin ID غير canonical.
- التقرير والتوثيق يطابقان ما يدخل APK فعليًا.

---

# 15. الخلاصة

أعلى عائد ليس إضافة عشرات المزايا فورًا؛ بل جعل الأساس موثوقًا ومستقلًا، ثم إبراز ما
يميز المشروع فعلًا: **بناء Android على الهاتف مع ضبط Gradle وفق الذاكرة والحرارة،
وعمل offline، ونظام إضافات مرن**.

ترتيب الاستثمار المقترح:

1. الثقة والأمان.
2. البناء القابل لإعادة الإنتاج.
3. الاختبارات والتعافي.
4. target SDK والتخزين الحديث.
5. تجربة الإضافات.
6. Build Lab والأداء.
7. العربية والتعليم.
8. AI والتعاون كإضافات اختيارية.

بهذا الترتيب يمكن تطوير المشروع دون تحويل النواة إلى monolith جديد أو التضحية بميزة
العمل على الأجهزة محدودة الموارد والاتصال.
