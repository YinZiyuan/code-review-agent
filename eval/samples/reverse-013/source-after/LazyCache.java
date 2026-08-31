public class LazyCache {
    private volatile Helper helper;
    public synchronized Helper get() {
        if (helper == null) {
            helper = new Helper();
        }
        return helper;
    }
    static class Helper {}
}
