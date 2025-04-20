package plc.project.analyzer;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * IMPORTANT: DO NOT CHANGE! This file is part of the Analyzer API and should
 * not be modified by your solution.
 */
public sealed interface Type {

    Primitive NIL = new Primitive("Nil");
    Primitive BOOLEAN = new Primitive("Boolean");
    Primitive INTEGER = new Primitive("Integer");
    Primitive DECIMAL = new Primitive("Decimal");
    Primitive STRING = new Primitive("String");

    Primitive ANY = new Primitive("Any");
    Primitive EQUATABLE = new Primitive("Equatable");
    Primitive COMPARABLE = new Primitive("Comparable");
    Primitive ITERABLE = new Primitive("Iterable");

    record Primitive(
        String name
    ) implements Type {}

    record Function(
        List<Type> parameters,
        Type returns
    ) implements Type {}

    record Object(Scope scope) implements Type {
        @Override
        public String toString() {
            // Print a summary of the scope without calling its full toString.
            // For example, we print the identity hash of the scope and a summary of its variable names.
            String scopeSummary = "scope@" + System.identityHashCode(scope);
            // If scope exposes its variables (e.g., as a Map), you can list just the keys:
            return "Object[" + scopeSummary + "]";
        }
    }


}
