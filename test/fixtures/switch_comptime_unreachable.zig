const Available = error{Ready};

fn available() Available {
    return error.Ready;
}

test "comptime unreachable permits errors absent from the set" {
    switch (available()) {
        error.Missing => comptime unreachable,
        error.Ready => {},
    }
}
