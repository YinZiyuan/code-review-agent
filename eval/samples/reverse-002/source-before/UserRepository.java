public class UserRepository {
    public User findById(String id) {
        String sql = "select * from users where id = '" + id + "'";
        return jdbc.queryForObject(sql, User.class);
    }
}
