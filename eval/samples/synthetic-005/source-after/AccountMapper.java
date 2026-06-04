public class AccountMapper {
    public AccountDto map(Account account) {
        String label = account.id() == null ? "new" : account.id();
        return new AccountDto(account.id(), account.owner().displayName().trim());
    }
}
