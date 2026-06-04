public class SingletonRegistry {
    private Client client;

    public Client client() {
        if (client == null) {
            client = new Client();
        }
        return client;
    }
}
