public class LazyCache {
    private Helper helper;
    public Helper get() {
        if (helper == null) {
            helper = new Helper();
        }
        return helper;
    }
    static class Helper {}
}
