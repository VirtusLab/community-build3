import com.google.gson.*
import java.lang.reflect.{
  ParameterizedType => JavaParameterizedType,
  Type => JavaType
}
import com.google.gson.reflect.TypeToken

def toJson[T](obj: T): String = adaptedGson.toJson(obj)
def fromJson[T](s: String)(using typeToken: TypeToken[T]) = adaptedGson.fromJson[T](s, typeToken.getType())
def fromJson[T](s: String, cls: Class[T]) = adaptedGson.fromJson[T](s, cls)


private lazy val adaptedGson = Gson().newBuilder
  .registerTypeAdapter(classOf[Option[_]], new OptionTypeAdapter())
  .registerTypeAdapter(classOf[List[_]], new ListTypeAdapter())
  .create()

private class ListTypeAdapter extends JsonSerializer[List[_]] {
  // serialize lists as arrays, instead we would get object with {"head": ?, "tail": ?}
  def serialize(
        value: List[_],
        valueType: JavaType,
        context: JsonSerializationContext
    ): JsonElement = {
      context.serialize(value.toArray)
    }
}

private class OptionTypeAdapter
    extends JsonSerializer[Option[_]]
    with JsonDeserializer[Option[_]] {
  def serialize(
      value: Option[_],
      valueType: JavaType,
      context: JsonSerializationContext
  ): JsonElement = {
    value.fold(JsonNull.INSTANCE)(context.serialize(_))
  }

  def deserialize(
      value: JsonElement,
      valueType: JavaType,
      context: JsonDeserializationContext
  ): Option[_] = {
    if (value == null) None 
    else 
      val parameterizedType = valueType.asInstanceOf[JavaParameterizedType]
      val innerType = parameterizedType.getActualTypeArguments()(0)
      Option(context.deserialize(value, innerType))
  }
}
