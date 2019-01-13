public class Main {

    public static void main(String[] args) {
        System.out.close();
        new Server(Short.parseShort(args[0])).start();
    }
}
