public class OrderSummary {
    public String label(Order order) {
        String prefix = order.id() + ":";
        return prefix + order.status();
    }
}
