import dev.mobilecodex.app.core.ProjectRegistry;
import dev.mobilecodex.app.core.Utf8Files;
import java.nio.file.Path;

/** JVM harness exercises the actual Android registry in desktop interoperability checks. */
public final class ProjectTransferHarness {
    public static void main(String[] args) throws Exception {
        Path state = Path.of(args[1]); ProjectRegistry registry;
        if (args[0].equals("init")) {
            registry = new ProjectRegistry(); registry.put("content://phone/tree/local-code", "Phone project", "phone-key");
        } else registry = ProjectRegistry.fromJson(Utf8Files.read(state));
        switch (args[0]) {
            case "init" -> { }
            case "import" -> registry.importProjects(Utf8Files.read(Path.of(args[2])));
            case "rename" -> registry.rename("phone-key",args[2]);
            case "export" -> Utf8Files.write(Path.of(args[2]),registry.exportProject("phone-key").toString());
            default -> throw new IllegalArgumentException("Unknown harness command");
        }
        Utf8Files.write(state,registry.toJson());
        System.out.println(registry.entries(p -> false));
    }
}
