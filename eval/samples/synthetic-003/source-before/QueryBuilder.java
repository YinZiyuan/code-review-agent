public class QueryBuilder {
    public String byStatus(String status) {
        return "select * from orders where status = ?";
    }
}
