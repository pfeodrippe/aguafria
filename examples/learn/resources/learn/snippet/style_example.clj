(ns learn.snippet.style-example
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defimport namespace-name "dir_name/file_name.zig" [])
(az/defimport TypeName "dir_name/TypeName.zig" [])

(az/defvar global-var :i32 k/undefined)
(az/defconst const-name 42)
(az/defconst PrimitiveTypeAlias (az/type :f32))
(az/defstruct StructName [[:field :i32]])
(az/defconst StructAlias StructName)

(az/defn- function-name :void [[parameter-name TypeName]]
  (let [function-pointer (k/var function-name)]
    (function-pointer)
    (k/= function-pointer other-function)
    (function-pointer)))

(az/defconst function-alias function-name)

(az/defn- ListTemplateFunction :type
  [[ChildType {:attrs #{k/comptime}} :type]
   [fixed-size {:attrs #{k/comptime}} :usize]]
  (List ChildType fixed-size))

(az/defn- ShortList :type
  [[T {:attrs #{k/comptime}} :type]
   [length {:attrs #{k/comptime}} :usize]]
  (az/struct
    [[:field_name [:array length T]]
     (az/fn- method-name :void [])]))

(az/defconst xml-document
  (az/multiline-string
   ["<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
    "<document>"
    "</document>"]))

(az/defstruct XmlParser [[:field :i32]])

(az/defn- read-u32-be :u32 [])
