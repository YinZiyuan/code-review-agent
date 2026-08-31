public class OrderExporter {
    public List<Row> export(List<Order> orders) {
        List<Row> rows = new ArrayList<>();
        for (Order order : orders) {
            Customer customer = customerRepository.findById(order.customerId());
            rows.add(Row.from(order, customer));
        }
        return rows;
    }
}
