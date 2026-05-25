public class AssertionHelper {
    public boolean passes(Runnable assertion) {
        try {
            assertion.run();
            return true;
        } catch (AssertionError e) {
            return false;
        }
    }
}
