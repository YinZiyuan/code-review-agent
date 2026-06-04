import java.util.List;
import java.util.Map;

public class InvoiceExporter {
    public List<Row> export(List<Invoice> invoices, CustomerRepository customers) {
        Map<String, Customer> byId = customers.findAllFor(invoices);
        return invoices.stream()
                .map(i -> new Row(i.id(), byId.get(i.customerId()).name()))
                .toList();
    }
}
