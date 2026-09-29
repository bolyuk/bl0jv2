package bl0.bl0jv2.data;

public final class OpCodes {

    public static final byte LOAD_NIL = 0x00;
    public static final byte LOAD_CONST = 0x01;

    public static final byte LR_ADD = 0x02;
    public static final byte LR_SUB = 0x03;
    public static final byte LR_MUL = 0x04;
    public static final byte LR_DIV = 0x05;
    public static final byte LR_REM = 0x06;

    public static final byte EQ = 0x07;
    public static final byte LESS = 0x08;
    public static final byte GREATER = 0x09;

    public static final byte MOV = 0x0A;
    public static final byte SET = 0x0B;

    public static final byte NEG = 0x0C;
    public static final byte NOT = 0x0D;

    public static final byte LR_POW = 0x0E;
    public static final byte NEW_ARRAY = 0x0F;

    public static final byte JUMP = 0x10;
    public static final byte JUMP_IF = 0x11;
    public static final byte JUMP_IF_NOT = 0x12;

    public static final byte CALL = 0x13;
    public static final byte RETURN = 0x14;
    public static final byte CALL_NATIVE = 0x15;

    public static final byte INDEX_GET = 0x16;
    public static final byte INDEX_SET = 0x17;
    public static final byte LENGTH = 0x18;
    // 0x19 (formerly TO_ARRAY) is free - toArr() moved to Bl0jv2_Prelude
    // once push()/len()/indexing made it expressible without an opcode

    public static final byte LR_AND = 0x1A;
    public static final byte LR_OR = 0x1B;
    public static final byte LR_XOR = 0x1C;
    public static final byte LR_SHL = 0x1D;
    public static final byte LR_SHR = 0x1E;
    public static final byte BIT_NOT = 0x1F;

    // 0x20 onward is unallocated - nothing requires staying within the
    // original 0x00-0x1F block, that was just the first author's reserved
    // buffer, not a format limit
    public static final byte PUSH = 0x20;
    public static final byte POP = 0x21;

    public static final byte TO_INT = 0x22;
    public static final byte TO_FLOAT = 0x23;
    public static final byte TO_STRING = 0x24;
    public static final byte TYPE_OF = 0x25;

    public static final byte NEW_TUPLE = 0x26;
    public static final byte UNPACK = 0x27;

    // 0x28 (formerly READ) is free - read() now goes through CALL_NATIVE
    // like every other native method, instead of its own dedicated opcode

    public static final byte TRY_ENTER = 0x29;
    public static final byte TRY_EXIT = 0x2A;
    public static final byte MAKE_ERR = 0x2B;

    public static final byte NEW_INSTANCE = 0x2C;
    public static final byte GET_FIELD = 0x2D;
    public static final byte SET_FIELD = 0x2E;
    public static final byte LOOKUP_METHOD = 0x2F;

    // unlike GET_FIELD/SET_FIELD, b is not a constant-pool name index but
    // the static field's own index directly - always resolved at compile
    // time, since 'ClassName.field' is always a literal class name
    public static final byte GET_STATIC_FIELD = 0x30;
    public static final byte SET_STATIC_FIELD = 0x31;

    // a captured (closed-over) local lives in a heap cell instead of a
    // plain register, so the enclosing function and any closure that
    // captures it always see the same value
    public static final byte MAKE_CELL = 0x32;
    public static final byte CELL_GET = 0x33;
    public static final byte CELL_SET = 0x34;

    // a's own FunDef in, closure object out; elements at a+1..a+b are the
    // captured cells' own references, same consecutive-registers
    // convention as NEW_ARRAY/NEW_TUPLE
    public static final byte MAKE_CLOSURE = 0x35;

    // frees a managed heap value (a REF) only - there is no VM-level raw
    // memory allocator (see RawMemory's own javadoc), so a plain int
    // reaching FREE is always a user error, not a raw-memory free
    public static final byte FREE = 0x36;

    // 0x37 (formerly ALLOC) is free - the VM no longer provides its own
    // raw-memory allocator; an OS built on this language allocates its own
    // structures on top of the raw address space, the same way it would on
    // real physical memory

    // a single flat raw-memory arena for kernel-style code (device
    // buffers, MMIO) - addresses are plain ints, no isolation between
    // regions (peek/poke can address anywhere in the arena)
    public static final byte PEEK = 0x38;
    public static final byte POKE = 0x39;

    // logical (non-sign-extending) right shift, unlike LR_SHR - matters
    // for hardware-register-style bit work, where a set high bit shouldn't
    // fill in with 1s the way an arithmetic shift would
    public static final byte LR_USHR = 0x3A;

    // fn (FunDef or closure) in a, [vector, priority] packed into b/b+1 -
    // same convention as POKE's [width, value]
    public static final byte REGISTER_HANDLER = 0x3B;

    // a and b are the address and size directly (both fit in plain operand
    // slots, unlike POKE which needs the consecutive-registers packing
    // trick) - marks [a, b) as reserved in the raw arena, see
    // RawMemory.reserve()
    public static final byte RESERVE = 0x3C;

    // fn in a, [core, arg] packed into b/b+1 - same convention as
    // REGISTER_HANDLER's [vector, priority]. Hands fn off to run on a
    // specific worker core (see Bl0jv2_jVM.CoreWorker), fire-and-forget -
    // the dispatching core never blocks on this
    public static final byte DISPATCH = 0x3D;

    // a second, port-addressed bus (0-65535), deliberately separate from
    // the raw memory arena - mirrors real x86 having two independent
    // address spaces (memory and I/O ports). Same operand shape as
    // PEEK/POKE: PORT_IN bakes the width in as a's compile-time immediate,
    // PORT_OUT packs [width, value] into b/b+1 exactly like POKE
    public static final byte PORT_IN = 0x3E;
    public static final byte PORT_OUT = 0x3F;

    // vector in a, arg in b - no packing needed, same shape as RESERVE.
    // Mutates a's own slot with the handler's return value (same
    // convention as POP/LOOKUP_METHOD). Synchronously invokes whatever
    // registerHandler() registered for that vector - the same 0-255 vector
    // table hardware interrupts use (see InterruptController), just
    // delivered immediately instead of on the next cooperative poll. This
    // is the only way user-mode code (see the privileged/unprivileged
    // split on Bl0jv2_jVM's CoreContext) can ask the kernel to do
    // something on its behalf, mirroring a real `syscall`/`int n` gate.
    public static final byte SYSCALL = 0x40;

    // both operate on a fixed 32-bit word at the given address, under
    // RawMemory's write lock for the duration of the read-modify-write -
    // this VM's reference-impl stand-in for a lock-prefixed x86
    // instruction. Not privilege-gated: real atomics are usable from user
    // mode too. Both mutate a's own slot to the value that was there
    // *before* the operation (matches lock cmpxchg's own semantics, and is
    // how a caller tells CAS success from failure: old == expected)
    //
    // ATOMIC_ADD: addr in a, delta in b directly - no packing needed, same
    // shape as RESERVE/SYSCALL
    public static final byte ATOMIC_ADD = 0x41;
    // ATOMIC_CAS: addr in a, [expected, newValue] packed into b/b+1 - same
    // trick as POKE
    public static final byte ATOMIC_CAS = 0x42;

    public static final byte HALT = (byte) 0xFF;
}
