package org.ib.ibbackend.persistencetest;

public class TestEven {
    public static void main(String[] args) {
        int n = 10;
        while (n >= 0) {
            if ( n%2==0)
                System.out.print(n+" ");
            n--;
        }
        int fact=1; int n1=5;
        for(int i=n1;i>0;i--){

                fact=fact*i;
        }
        System.out.println("factorial NUmber : "+fact);

    }
}
