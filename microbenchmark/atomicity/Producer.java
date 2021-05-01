package atomicity;

import java.util.Vector;

class Producer extends Thread { 
  static final int MAXQUEUE = 5; 
  int a;
  private Vector<String> messages = new Vector<String>(); 

  public void run() { 
      try { 
          while ( true ) { 
              putMessage(); 
              sleep( 1000 ); 
          } 
      }  
      catch( InterruptedException e ) { } 
  } 

  private synchronized void putMessage() 
      throws InterruptedException { 
      
      while ( messages.size() == MAXQUEUE ) 
          wait(); 
      //messages.addElement( new java.util.Date().toString() ); 
      messages.addElement(Integer.toString(++a));
      notify(); 
  } 

  // Called by Consumer 
  public synchronized String getMessage() 
      throws InterruptedException { 
      notify(); 
      while ( messages.size() == 0 ) 
          wait(); 
      //String message = (String)messages.firstElement(); 
      String message = messages.firstElement();
      messages.removeElement( message ); 
      return message; 
  } 
} 
