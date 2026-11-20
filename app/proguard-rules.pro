# R8: правила проекта.
#
# Стек сериализации и Room поставляют собственные consumer-правила, поэтому здесь только
# то, что R8 не может вывести сам: имена полей JSON-моделей читаются рефлексией
# kotlinx.serialization, а Retrofit требует сохранения аннотаций методов API.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# kotlinx.serialization: сериализаторы генерируются плагином и находятся по companion-объекту.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <1>$<2>$* {
    static <1>$<2> <3>(...);
}
-keepclassmembers class ** {
    *** Companion;
}

# Retrofit: прокси-интерфейсы и их аннотации.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# DTO JSON: R8 не считает ссылкой тип-параметр `Response<EmployeeResponse>` в сигнатуре
# suspend-функции Retrofit — в release классы ответов выкидывались (usage.txt:
# EmployeeResponse, DeleteEmployeeResponse), и kotlinx не находил сериализатор →
# «Внутренняя ошибка приложения» при добавлении/удалении сотрудника (20.11).
# Держим все DTO с их $$serializer и Companion: классы крошечные, цена нулевая.
-keep class io.github.bigsprut.taskman.data.remote.dto.** { *; }

# OkHttp: опциональные платформенные интеграции (см. правила OkHttp для R8).
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
