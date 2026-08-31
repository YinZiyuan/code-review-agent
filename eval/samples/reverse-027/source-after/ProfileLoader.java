public class ProfileLoader {
    public Profile load(String id) {
        try {
            return fetch(id);
        } catch (RemoteException e) {
            return null;
        }
    }
}
