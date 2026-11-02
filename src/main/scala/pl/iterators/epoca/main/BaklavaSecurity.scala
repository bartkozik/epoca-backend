package pl.iterators.epoca.main

import com.typesafe.config.Config

object BaklavaSecurity {
  def validate(config: Config): Unit =
    if (config.hasPath("baklavaRoutes.enabled") && config.getBoolean("baklavaRoutes.enabled")) {
      def credential(path: String): String = if (config.hasPath(path)) config.getString(path).trim else ""
      val user                             = credential("baklavaRoutes.basicAuthUser")
      val password                         = credential("baklavaRoutes.basicAuthPassword")
      require(user.nonEmpty && !user.equalsIgnoreCase("admin"), "Enabled Baklava routes require a nondefault username")
      require(password.nonEmpty && !password.equalsIgnoreCase("password"), "Enabled Baklava routes require a nondefault password")
    }
}
