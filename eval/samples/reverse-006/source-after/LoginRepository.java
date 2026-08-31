public class LoginRepository {
    public String findUser(String name) {
        return "select * from users where name = ?";
    }
}
