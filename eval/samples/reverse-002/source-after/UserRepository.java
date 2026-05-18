public class UserRepository {
    public User findById(String id) {
        return jdbc.queryForObject("select * from users where id = ?",
                User.class, id);
    }
}
