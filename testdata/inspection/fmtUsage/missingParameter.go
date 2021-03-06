package main

import "fmt"

func TestPrintf() {
    fmt.Printf("%*.*d\n", 5, 3, 4)

    fmt.Printf("%d, /*begin*/%d/*end.Missing parameter*/\n", 5)
    fmt.Printf("/*begin*/%f/*end.Missing parameter*/\n")
}

func TestFprintf() {
    fmt.Fprintf(nil, "%d, /*begin*/%d/*end.Missing parameter*/\n", 15)
    fmt.Fprintf(nil, "/*begin*/%f/*end.Missing parameter*/\n")
}

func TestScanf() {
    d := new(int)
    fmt.Scanf("%d/*begin*/%d/*end.Missing parameter*/", d)
    fmt.Scanf("/*begin*/%f/*end.Missing parameter*/")
}

func TestFscanf() {
    d := new(int)
    fmt.Fscanf(nil, "%d/*begin*/%d/*end.Missing parameter*/", d)
    fmt.Fscanf(nil, "/*begin*/%f/*end.Missing parameter*/")
}