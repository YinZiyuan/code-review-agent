import java.util.List;

public class OrderRepository {
    public void enrich(List<Integer> ids) {
        for (Integer id : ids) {
            loadCustomer(id);
        }
    }
    private Object loadCustomer(Integer id) { return id; }
}
