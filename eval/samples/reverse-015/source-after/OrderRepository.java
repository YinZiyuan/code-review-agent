import java.util.List;

public class OrderRepository {
    public void enrich(List<Integer> ids) {
        loadCustomers(ids);
    }
    private Object loadCustomers(List<Integer> ids) { return ids; }
}
