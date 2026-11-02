import java.nio.file.*;
import org.springframework.security.crypto.bcrypt.BCrypt;
class HashPassword {
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    Path output = root.resolve("password.bcrypt");
    if (!Files.exists(output)) {
      Files.writeString(output, BCrypt.hashpw(Files.readString(root.resolve("password")), BCrypt.gensalt(8)));
    }
  }
}
