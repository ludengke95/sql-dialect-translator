import com.translator.core.DialectType;
import com.translator.core.SqlTranslator;
import com.translator.core.config.TranslationConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Quick diagnostic: show how Q21 is translated.
 *
 * Compile + run:
 *   javac -cp sdt-core/target/sdt-core-1.0.0-SNAPSHOT.jar:$(find ~/.m2 -name 'calcite-core-*.jar' | head -1):$(find ~/.m2 -name 'slf4j-api-*.jar' | head -1) TranslateDiagnostic.java
 *   java -cp .:sdt-core/target/sdt-core-1.0.0-SNAPSHOT.jar:$(find ~/.m2 -name 'calcite-core-*.jar' | head -1):$(find ~/.m2 -name 'slf4j-api-*.jar' | head -1):$(find ~/.m2 -name 'guava-*.jar' | head -1) TranslateDiagnostic
 */
public class TranslateDiagnostic {
    public static void main(String[] args) throws Exception {
        String sql = new String(Files.readAllBytes(Paths.get(args.length > 0 ? args[0] : "benchmark/tpch/queries/q21.sql")), StandardCharsets.UTF_8);

        System.out.println("========== ORIGINAL SQL (MySQL) ==========");
        System.out.println(sql);
        System.out.println();

        SqlTranslator translator = new SqlTranslator(DialectType.MYSQL, DialectType.POSTGRESQL, TranslationConfig.DEFAULT);

        try {
            String translated = translator.translate(sql);
            System.out.println("========== TRANSLATED SQL (PostgreSQL) ==========");
            System.out.println(translated);
        } catch (Exception e) {
            System.out.println("========== TRANSLATION FAILED ==========");
            e.printStackTrace();
        }
    }
}