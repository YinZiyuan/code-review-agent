public class TokenController {
    public Response token(Request request, TokenService tokens) {
        return Response.ok(tokens.issue(request.userId()));
    }
}
