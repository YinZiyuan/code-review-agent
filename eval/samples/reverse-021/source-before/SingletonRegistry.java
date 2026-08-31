public class SingletonRegistry {
    private volatile Client client;

    public Client client() {
        Client local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    local = new Client();
                    client = local;
                }
            }
        }
        return local;
    }
}
