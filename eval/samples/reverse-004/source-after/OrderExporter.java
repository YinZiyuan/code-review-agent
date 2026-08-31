public class OrderExporter {
    public List<Row> export(List<Order> orders) {
        Map<Long, Customer> customers = customerRepository.findAllByIds(
                orders.stream().map(Order::customerId).toList());
        List<Row> rows = new ArrayList<>();
        for (Order order : orders) {
            Customer customer = customers.get(order.customerId());
            rows.add(Row.from(order, customer));
        }
        return rows;
    }
}
