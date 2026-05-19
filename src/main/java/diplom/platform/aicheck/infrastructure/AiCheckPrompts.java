package diplom.platform.aicheck.infrastructure;

import java.util.List;
import java.util.regex.Pattern;

import diplom.platform.aicheck.domain.AiCheckRequest;
import diplom.platform.aicheck.domain.AiCheckSourceType;
import diplom.platform.aicheck.domain.AiCheckSubject;

/**
 * Системные промпты под предметную область и тип материала + строгая JSON-инструкция,
 * чтобы ответ был детерминированно разбираемым.
 */
public final class AiCheckPrompts {

    private static final Pattern METADATA_LINE = Pattern.compile(
            "(?iu)^(предмет|дисциплина|курс|семестр|тема|вариант|группа|фио|студент)\\s*[:\\-–].*$");

    private AiCheckPrompts() {
    }

    /**
     * Студент не указал содержательных критериев от преподавателя — только пустота, метаданные
     * (предмет/группа) или очень короткая подсказка без признаков требований.
     */
    private static boolean lacksExplicitTeacherCriteria(String custom) {
        if (custom == null || custom.isBlank()) {
            return true;
        }
        String normalized = custom.replace('\r', '\n').trim();
        List<String> lines = normalized.lines().map(String::strip).filter(s -> !s.isBlank()).toList();
        if (lines.isEmpty()) {
            return true;
        }
        boolean onlyMetadataLines = lines.stream().allMatch(line -> METADATA_LINE.matcher(line).matches());
        if (onlyMetadataLines && lines.size() <= 4) {
            return true;
        }
        if (normalized.length() < 100 && !hasTeacherCriterionSignals(normalized)) {
            return true;
        }
        return false;
    }

    private static boolean hasTeacherCriterionSignals(String text) {
        String lower = text.toLowerCase();
        return lower.contains("критери")
                || lower.contains("требован")
                || lower.contains("ожида")
                || lower.contains(" необходимо")
                || lower.contains("нужно выполнить")
                || lower.contains("оценива")
                || lower.contains(" техническое задание")
                || lower.contains(" по заданию")
                || lower.contains(" условие ");
    }

    /**
     * Для этих запросов платформа требует осмысленный набор исполнимых тестов в JSON (см. контракт ответа и повторный запрос модели при нарушении).
     */
    public static boolean requiresMandatoryExecutableGeneratedTests(AiCheckSubject subject, AiCheckSourceType sourceType) {
        if (sourceType == AiCheckSourceType.CODE_ARCHIVE || sourceType == AiCheckSourceType.CODE_GIT) {
            return true;
        }
        return sourceType == AiCheckSourceType.TEXT && subject == AiCheckSubject.CS;
    }

    public static String regenerationHintRetryFullJsonMandatoryTests() {
        return """
                КРИТИЧНО — ОДИН ПОВТОР ВЫДАЧИ: предыдущий ответ отклонён технически: в JSON массив generated_tests отсутствует или в нём меньше 3 тестов либо хотя бы у одного теста пустые command и/или docker_image.
                Выдай ЗАНОВО один целостный JSON по всей схеме (все ключи: score, summary, detailed_feedback, strengths, issues, findings, generated_tests, recommendations), не сокращая остальные разделы.
                Требование: generated_tests содержит минимум 3 элемента; у каждого обязательно непустые name, purpose, target, expected_result, kind, command (одна реальная shell-команда из корня присланного проекта) и docker_image (валидный oci ref, для JDK — eclipse-temurin:21-jdk или maven/gradle-образ под фактический pom/gradlew).
                """;
    }

    public static String systemPrompt(AiCheckSubject subject, AiCheckSourceType sourceType) {
        String role = switch (subject) {
            case MATH -> "Ты опытный преподаватель математики. Проверяй вычисления, обозначения и логику доказательства.";
            case PHYSICS -> "Ты преподаватель физики. Проверяй применение законов, корректность размерностей и анализ эксперимента.";
            case CS -> "Ты senior software engineer и преподаватель программирования. Проверяй корректность алгоритма, структуру кода, обработку ошибок, тесты.";
            case HISTORY -> "Ты преподаватель истории. Проверяй факты, даты, причинно-следственный анализ и работу с источниками.";
            case LITERATURE -> "Ты преподаватель литературы. Оценивай глубину анализа, аргументацию и язык.";
            case LANGUAGE -> "Ты преподаватель языка. Оценивай грамматику, лексику, стиль и связность.";
            case BIOLOGY -> "Ты преподаватель биологии. Проверяй фактическую точность, использование терминов и логику объяснений.";
            case CHEMISTRY -> "Ты преподаватель химии. Проверяй уравнения, баланс реакций, размерности и расчёты.";
            case GENERAL -> "Ты опытный академический рецензент. Проверяй точность, структуру и обоснованность работы.";
        };
        String hint = switch (sourceType) {
            case IMAGE -> " На вход подаётся изображение с работой студента — внимательно прочитай рукописный/печатный текст и формулы, прежде чем оценивать; при неполной читаемости опирайся на то, что удаётся распознать, помечая неуверенность в detailed_feedback, без отказа выдать JSON.";
            case CODE_ARCHIVE -> " На вход подаётся структура и ключевые файлы проекта. Сначала восстанови архитектуру, затем проверь код по существу. Для каждой найденной проблемы указывай file_path и line_start/line_end если это можно определить.";
            case CODE_GIT -> " На вход подаётся снимок репозитория. Учти структуру проекта и зависимости. Для каждой найденной проблемы указывай file_path и line_start/line_end если это можно определить.";
            case DOCUMENT -> " На вход подаётся текстовый документ. Проверяй построчно, при необходимости цитируй фрагменты.";
            case TEXT -> "";
        };
        String noCriteriaRule = " Если явных критериев проверки нет или указан только предмет — сам определи по материалу студента тип работы и оценивай по разумным стандартам для этого типа. Нельзя ограничиваться общими фразами без привязки к тексту, нельзя просить студента или платформу уточнять условие задания или ответать «как есть» без содержательного разбора.";
        String antiDumbModel = " Не отказывайся от проверки и не утверждай, что данных мало или задание не видно — при отсутствии явного ТЗ восстанови цель из приложенной работы. Не задавай вопросов читателю и платформе. Не подменяй разбор этическими отказами, общими нравоучениями или пересказом условия вместо оценки. Работа обрезана, на другом языке или с браком вёрстки — всё равно продолжай в рамках требуемого машиночитаемого JSON.";
        String mandatoryExecutable = "";
        if (requiresMandatoryExecutableGeneratedTests(subject, sourceType)) {
            mandatoryExecutable =
                    " Для этой проверки ты ОБЯЗАТЕЛЬНО возвращаешь в JSON массив generated_tests не менее чем из трёх тестов (через поле command платформа запускает код): у каждого теста непустые command и docker_image, согласованные с реальными файлами студента. Пустые command, отсутствие generated_tests или один тест-заглушка — недопустимы; ответ будет отклонён и запрошен заново.";
        }
        return role + hint + noCriteriaRule + antiDumbModel + mandatoryExecutable
                + " Веди себя как доброжелательный преподаватель, дающий конкретные шаги улучшения.";
    }

    /**
     * Жёсткая инструкция формата JSON, чтобы парсер не угадывал.
     */
    public static String outputContract(AiCheckSubject subject, AiCheckSourceType sourceType) {
        String base = """
                Верни ответ строго в JSON по следующей схеме (без преамбулы, без блоков ```):
                {
                  "score": число от 0 до 100,
                  "summary": "краткий вердикт 1-2 предложения",
                  "detailed_feedback": "развернутый разбор по пунктам",
                  "strengths": ["сильная сторона", ...],
                  "issues": ["что исправить", ...],
                  "findings": [
                    {
                      "file_path": "src/main/java/Foo.java",
                      "line_start": 10,
                      "line_end": 14,
                      "severity": "critical|major|minor|info",
                      "title": "короткий заголовок проблемы",
                      "explanation": "почему это проблема и как исправить",
                      "snippet": "фрагмент кода (опционально)"
                    }
                  ],
                  "generated_tests": [
                    {
                      "name": "название теста",
                      "kind": "unit|integration|e2e|manual",
                      "purpose": "какой риск покрывает",
                      "target": "файл/модуль/функция",
                      "steps": ["шаг 1", "шаг 2"],
                      "expected_result": "ожидаемый результат",
                      "command": "Ровно одна shell-команда из корня репозитория (компиляция + запуск или junit/pytest). Для Scanner достаточно java … — платформа подставит ввод сама из steps, либо укажи поле stdin.",
                      "docker_image": "Docker-образ или пустая строка. Для JDK указывай eclipse-temurin:21-jdk (или :17-jdk и т.д.); не используй openjdk:* — теги на Hub часто недоступны. Примеры: python:3.12, maven:3.9-eclipse-temurin-21, gcc:14.",
                      "stdin": "Строки ввода для программы через \\n (например \"2\\\\nflour\\\\nbake\\\\n\") если первая строка — число циклов, далее команды lab. Если пусто, для Java со Scanner платформа попробует собрать ввод из steps."
                    }
                  ],
                  "recommendations": [
                    {"title": "название материала", "description": "1-2 предложения, почему полезно", "resource_type": "video|article|book|exercise|course", "url": "https://..." }
                  ]
                }
                В начале summary (одно короткое предложение) укажи, как ты понимаешь задание/тип работы, если формулировка от студента была неясной — затем вердикт.
                Если критериев от преподавателя нет, найди задачу в самом содержании работы и оценивай по ней; не отмалчивайся и не переходи в «свободный текст» — только это JSON.

                Строго по формату (иначе платформа не разберёт ответ): первый символ всего сообщения — «{», последний — «}»; один корневой объект; без текста до/после JSON; без обёртки ```; без комментариев // и /* */; без хвостовых запятых; без неэкранированных переводов строк внутри строк JSON; ключи и имена полей только как в схеме (латиница, snake_case); строки в двойных кавычках; score — число 0..100 без кавычек; не дублируй два JSON-подряд; не подставляй буквальные плейсхолдеры из описания схемы («...», «число от 0 до 100», пример Foo.java, перечисление critical|major как одна строка-заглушка).
                Запрещено в тексте полей: просьбы уточнить задание, риторические вопросы читателю, отказ проверять, одна только общая похвала или один общий слив без пунктов по содержимому.

                Полнота и честность: strengths и issues — не оба пустые; хотя бы 2–4 содержательных пункта суммарно, привязанных к фактам из работы (цитата, номер строки, факт, формула). Не выдумывай findings: каждый finding должен опираться на реальный фрагмент материала; file_path только из присланных путей (или "" если файлов не было — тогда без вымышленных путей). Не искусственно доводить число проблем до трёх — если блокеров нет, добавь короткие info/minor улучшения или оставь меньше пунктов с объяснением в detailed_feedback.

                Recommendations: 3–6 элементов по реальным слабым местам; url — рабочий https или пустая строка (без выдуманных доменов и «example» как обязательных).

                Для sourceType CODE_ARCHIVE/CODE_GIT/TEXT с кодом:
                - findings: при реальных дефектах — конкретно; не копируй шаблон «critical|major» в текст как одну строку.
                - generated_tests: 3–8 реалистичных; command исполним из корня проекта как в дереве файлов студента (не абстрактные mvn без pom и т.п. если их нет).
                - Поле command: одна shell-команда без markdown и без второй команды после перевода строки без нужды; docker_image — валидный oci ref без пояснений в скобках и без переменных shell и без «latest»-псевдоописаний вместо тега; jdk: eclipse-temurin, не openjdk.
                - Не включай в JSON неэкранированные сырые переводы строк внутри значений там, где это ломает JSON — используй \\n или укорачивай snippet.
                - steps: для интерактивных программ перечисляй вход построчно латиницей или в stdin; без воды («пользователь открывает терминал…»).
                - Для лаб с циклом и Scanner укажи в issues при уместности: без while (sc.hasNextLine()) при исчерпании stdin возможен NoSuchElementException; тесты с намеренной нехваткой ингредиентов должны задавать полный stdin или exit/break.
                - Матрицы (n, m и элементы через Scanner): ОБЯЗАТЕЛЬНО сверь с кодом студента, как именно читаются размеры и ячейки — одна строка на все n·m чисел через пробел или отдельные строки на каждую строку матрицы и т.д. Число целых в stdin после размеров должно точно совпадать с тем, сколько элементов код реально считывает для «валидного» случая (для паттерна int[n][m] из одной split-строки — ровно n·m токенов). Неправильный пример: n=3, m=3, а в строке данных только шесть чисел — программа закономерно упадёт; такой позитивный тест браковать и исправить.
                - Если в коде нет проверки «число токенов == n·m» перед заполнением, при нехватке/избытке чисел возможно ArrayIndexOutOfBoundsException или другое исключение: в generated_tests для негативных сценариев указывай expected_result честно (например фактический вывод + stack trace или «падение с AIOOBE»), либо формулируй тест как «ожидается ERROR» только если в задании явно требуется валидация ввода и это отражено в коде/ТЗ.
                - Команда запуска java должна учитывать package: например java -cp . package.sub.ClassName, а не java ClassName при объявленном package.
                - Если студент читает n и m отдельными nextLine(), а затем всю матрицу как одну строку с split(\" \") — можно задать ввод тремя блоками через \\n в поле stdin; либо в steps перечислить подряд n, затем m, затем все n·m целых (платформа сворачивает их в одну строку данных при этом паттерне кода).
                """;
        if (requiresMandatoryExecutableGeneratedTests(subject, sourceType)) {
            return base + mandatoryExecutableGeneratedTestsAddon();
        }
        return base;
    }

    private static String mandatoryExecutableGeneratedTestsAddon() {
        return """

                ОБЯЗАТЕЛЬНО ДЛЯ ЭТОГО ЗАПРОСА — иначе платформа отвергнет ответ и запросит повтор:
                - Поле "generated_tests" обязано существовать и содержать минимум 3 объекта-теста.
                - У каждого: непустые name, kind, purpose, target, expected_result; "command" — ровно одна непустая shell-команда, исполнимая из корня присланного проекта (соответствует фактическому дереву файлов).
                - У каждого: непустое "docker_image" (валидный registry/name:tag; для Java-модулей без контейнерной магии — eclipse-temurin:21-jdk или maven:3.9-eclipse-temurin-21 при pom.xml; без openjdk:*).
                - Сценарии разнообразны: успешный путь, ошибка/неверный ввод, граничный случай по смыслу лабораторной (для Scanner — см. steps/stdin выше).
                - В stdin для матриц и сеток: количество переданных чисел и порядок строк должны матчить фактическое чтение в коде (в т.ч. n·m при одной split-строке).
                - Пустые command/docker_image или отсутствие массива — запрещены.
                """;
    }

    public static String userPrompt(AiCheckRequest request) {
        StringBuilder sb = new StringBuilder();
        String custom = request.customInstructions() == null ? "" : request.customInstructions().trim();
        boolean lacksCriteria = lacksExplicitTeacherCriteria(custom);
        if (!custom.isBlank() && lacksCriteria) {
            sb.append("""
                    Критерии выполнения задания от преподавателя не заданы (есть только подсказка по предмету или поле пустое).
                    Самостоятельно восстанови, что это за работа по присланному материалу, и проверь по содержательным для этой задачи правилам. Начни summary с явной интерпретации задания.
                    Если ниже указан предмет — используй только как общий контекст, не считать это полным техническим заданием.

                    Контекст (предмет / поле студента без детальных критериев):
                    """);
            sb.append(custom).append("\n\n");
        } else if (!custom.isBlank()) {
            sb.append("Дополнительные инструкции преподавателя (критерии и ограничения): ").append(custom).append("\n\n");
        } else {
            sb.append("""
                    Критерии задания не переданы — определи по работе студента её цель и ожидаемый формат выполнения, затем проверь по содержательным для этой цели правилам. Начни summary с краткой формулировки того, какую задачу ты видишь в материале.

                    """);
        }
        if (request.preparedContent() != null && !request.preparedContent().isBlank()) {
            sb.append(request.preparedContent());
            if (!request.preparedContent().endsWith("\n")) {
                sb.append("\n");
            }
        } else if (request.studentText() != null && !request.studentText().isBlank()) {
            sb.append("Работа студента:\n").append(request.studentText().trim()).append("\n");
        }
        String regenHint = request.regenerationHintForUserPrompt();
        if (regenHint != null && !regenHint.isBlank()) {
            sb.append("\n").append(regenHint.trim()).append("\n");
        }
        sb.append('\n').append(outputContract(request.subject(), request.sourceType()));
        return sb.toString();
    }
}
