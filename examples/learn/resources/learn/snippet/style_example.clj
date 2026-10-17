(ns learn.snippet.style-example
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defimport namespace-name "dir_name/file_name.zig" [])
(a/defimport TypeName "dir_name/TypeName.zig" [])

(a/defvar global-var :i32 undefined)
(a/defconst const-name 42)
(a/defconst PrimitiveTypeAlias (a/type :f32))
(a/defstruct StructName [[:field :i32]])
(a/defconst StructAlias StructName)

(a/defn- function-name :void [[parameter-name TypeName]]
  (let [function-pointer (k/var function-name)]
    (function-pointer)
    (= function-pointer other-function)
    (function-pointer)))

(a/defconst function-alias function-name)

(a/defn- ListTemplateFunction :type
  [[ChildType {:attrs #{comptime}} :type]
   [fixed-size {:attrs #{comptime}} :usize]]
  (List ChildType fixed-size))

(a/defn- ShortList :type
  [[T {:attrs #{comptime}} :type]
   [length {:attrs #{comptime}} :usize]]
  (a/struct
   [[:field_name [:array length T]]
    (a/fn- method-name :void [])]))

(a/defconst xml-document
  (a/multiline-string
   ["<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
    "<document>"
    "</document>"]))

(a/defstruct XmlParser [[:field :i32]])

(a/defn- read-u32-be :u32 [])
