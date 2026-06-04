import org.junit.jupiter.api.Test;

class PaymentServiceTest {
    @Test
    void rejectsNegativeAmount() {
        PaymentService service = new PaymentService();
        service.charge(-1);
    }
}
