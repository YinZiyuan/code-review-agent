public class AccountMapper {
    public AccountDto map(Account account) {
        return new AccountDto(account.id(), account.owner().displayName());
    }
}
