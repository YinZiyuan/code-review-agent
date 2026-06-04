public class ProfileLoader {
    public Profile load(String id) {
        try {
            return fetch(id);
        } catch (RemoteException e) {
            throw new IllegalStateException("profile fetch failed for " + id, e);
        }
    }
}
