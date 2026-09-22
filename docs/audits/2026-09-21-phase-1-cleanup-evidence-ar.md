# دليل تنظيف المرحلة الأولى

التاريخ: 2026-09-21

النطاق: انحراف مخطط Gradle وبقايا الميزات التي نُقلت إلى إضافات، من دون تغيير المعمارية الأساسية أو حذف مكوّن مستخدم.

## طريقة الإثبات قبل الحذف

1. حُلِّلت جميع المسارات المعلنة في `settings.gradle.kts` وقورنت بمجلدات المشاريع وملفات `build.gradle*`.
2. بُحث عن مراجع المشاريع والحزم والأسماء في Kotlin وJava وGradle وXML والوثائق النشطة.
3. قورنت نسخة `vectormaster/` غير المعلنة بالتنفيذ المستخدم فعليًا داخل `xml-inflater/.../vectormaster`.
4. قورنت aliases في `gradle/libs.versions.toml` بكل ملفات Gradle/Kotlin/Java؛ حُذفت فقط aliases التي لم تملك أي مستهلك.
5. فُحصت موارد AI وCompose Preview بالاسم (`R.string.*` و`@string/*` والسلاسل الديناميكية) قبل حذفها. حُفظت الموارد العامة المستخدمة، كما حُفظت واجهات التكامل التي تحتاجها الإضافات.

## النتائج والإجراء

| العنصر | الدليل | الإجراء |
|---|---|---|
| `:compose-preview` | المسار معلن لكن المجلد غير موجود؛ الميزة نُقلت إلى إضافة مستقلة | إزالة الإعلان القديم وتحديث ADR 0009 والوثيقة المعمارية |
| `subprojects/project-serial` و`project-serialization` | غير معلنين في Gradle ولا توجد مراجع إليهما | حذف النسختين اليتيمتين |
| `vectormaster/` | غير معلن؛ التنفيذ ذي الحزمة المختلفة موجود ومستخدم داخل `xml-inflater` | حذف النسخة المكررة فقط، والإبقاء على تنفيذ `xml-inflater` |
| `subprojects/llama.cpp` والـscripts وملف version | submodule فارغ وأدوات بلا مسار بناء أو مستهلك؛ AI/llama خارج النواة | حذف البقايا وتحديث ADR 0005 والمعمارية |
| بقايا AI/Compose resources | لا مراجع مصدرية؛ الملفات لا تملك Activity/Fragment أو مسار تنقل | حذف الملفات والمفاتيح الميتة مع إبقاء واجهات plugin integration |
| `E2E_TESTING_REPORT.md` | تقرير قديم يصف نواة لم تعد مطابقة للشجرة الحالية | حذفه؛ التدقيق الحالي وتقارير CI هما المرجعان |
| Spotless/hook/CODEOWNERS | استثناءات ومسارات `LayoutEditor` و`llama.cpp` غير موجودة | إزالة الاستثناءات القديمة |
| version catalog | 14 alias بلا مستهلك و9 مفاتيح version أصبحت يتيمة | حذفها فقط؛ الإبقاء على aliases المتشابهة المستخدمة |

## ما حُفظ عمدًا

- `uidesigner` و`xml-inflater`: كلاهما داخل مخطط البناء وله مستهلكون فعليون.
- `xml-inflater/.../vectormaster`: هو التنفيذ المستخدم في تحليل vector drawables.
- وظائف `ModuleProject` التي تُرجع classpaths: ما زالت عقد تكامل مفيدة لإضافة Compose Preview الخارجية.
- واجهات `IDEApiFacade` وقراءة build output: تستخدمها إضافات automation/AI خارج النواة.
- موارد Layout Editor العامة المهاجرة التي ما زالت تملك مراجع من شاشات المشروع الحالية.

## منع عودة الانحراف

أضيف `scripts/validate_repository_structure.py`، وهو فحص مستقل عن JDK يتحقق من:

- أن كل root module معلن يملك مجلدًا وملف بناء.
- عدم وجود مشروع Gradle جذري غير معلن (مع استثناء builds المستقلة وfixtures).
- بقاء المسارات والمراجع المتوقفة محذوفة.
- بقاء aliases ومفاتيح versions المثبت عدم استخدامها محذوفة.

النتيجة بعد التنظيف: **85 module معلنًا، كلها قابلة للحل، ولا مشروع جذري غير معلن**.

## التحقق المتاح

نجحت الفحوص الآتية:

- `python3 scripts/validate_repository_structure.py`
- تحليل XML لكل ملفات `resources/src/main/res/values*/strings.xml`
- `git diff --check`

لم يُدعَ نجاح Gradle build في هذه النقطة: بيئة العمل لا تحتوي JDK/Android SDK، ومحاولتا تنزيل JDK عبر APT وGitHub release assets فشلتا بسبب قيود الشبكة. يجب تنفيذ Gradle configuration/build في بيئة CI معزولة أو بعد توفير JDK 17 وSDK 36 قبل اعتماد هذه المجموعة نهائيًا.
