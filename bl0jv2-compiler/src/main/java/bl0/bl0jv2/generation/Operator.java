package bl0.bl0jv2.generation;

public enum Operator {
    PLUS,           //   +
    MINUS,          //   -
    STAR,           //   *
    DIV,            //   /
    ASSIGNMENT,     //   =
    REMAINDER,      //   %

    EQUALS,         //   ==
    NOT_EQUALS,     //   !=
    NOT,            //   !

    LESS,           // <
    LESS_EQUALS,    // <=
    GREATER,        // >
    GREATER_EQUALS, // >=

    PLUS_PLUS,
    MINUS_MINUS,
    STAR_STAR,

    BIT_AND,        // &
    BIT_OR,         // |
    BIT_XOR,        // ^
    BIT_NOT,        // ~
    SHIFT_LEFT,     // <<
    SHIFT_RIGHT,    // >>
    SHIFT_RIGHT_UNSIGNED, // >>> (logical, not sign-extending - matters for hardware-register-style bit work)

    AND,            // && (short-circuit)
    OR,             // || (short-circuit)

    // compound assignment: 'x += 1' means 'x = x + 1' (see Bl0jv2_Parser)
    PLUS_ASSIGN,            // +=
    MINUS_ASSIGN,           // -=
    STAR_ASSIGN,            // *=
    DIV_ASSIGN,             // /=
    REMAINDER_ASSIGN,       // %=
    STAR_STAR_ASSIGN,       // **=
    AND_ASSIGN,             // &=
    OR_ASSIGN,              // |=
    XOR_ASSIGN,             // ^=
    SHIFT_LEFT_ASSIGN,      // <<=
    SHIFT_RIGHT_ASSIGN,     // >>=
    SHIFT_RIGHT_UNSIGNED_ASSIGN, // >>>=
}
