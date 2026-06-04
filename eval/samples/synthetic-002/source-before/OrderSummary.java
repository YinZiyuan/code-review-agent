public class OrderSummary {
    public String label(Order order) {
        return order.id() + ":" + order.status();
    }
}
