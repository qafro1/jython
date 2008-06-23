// Autogenerated AST node
package org.python.antlr.ast;
import org.python.antlr.PythonTree;
import org.antlr.runtime.CommonToken;
import org.antlr.runtime.Token;
import java.io.DataOutputStream;
import java.io.IOException;

public class aliasType extends PythonTree {
    public String name;
    public String asname;

    public static final String[] _fields = new String[] {"name","asname"};

    public aliasType(Token token, String name, String asname) {
        super(token);
        this.name = name;
        this.asname = asname;
    }

    public aliasType(int ttype, Token token, String name, String asname) {
        super(ttype, token);
        this.name = name;
        this.asname = asname;
    }

    public aliasType(PythonTree tree, String name, String asname) {
        super(tree);
        this.name = name;
        this.asname = asname;
    }

    public String toString() {
        return "alias";
    }

    public <R> R accept(VisitorIF<R> visitor) throws Exception {
        traverse(visitor);
        return null;
    }

    public void traverse(VisitorIF visitor) throws Exception {
    }

}
