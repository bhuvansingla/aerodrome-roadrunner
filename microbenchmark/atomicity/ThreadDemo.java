package atomicity;

class ThreadDemo {
   public static void main(String args[]) {
     new NewThread("firstchild"); // create a new thread
     new NewThread("secondchild");//create a third thread
   }
}
