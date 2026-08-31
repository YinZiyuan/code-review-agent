import java.util.List;

public class InvoiceExporter {
    public List<Row> export(List<Invoice> invoices, CustomerRepository customers) {
        return invoices.stream()
                .map(i -> new Row(i.id(), customers.findById(i.customerId()).name()))
                .toList();
    }
}
